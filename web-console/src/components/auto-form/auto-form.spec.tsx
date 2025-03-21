/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { COMPACTION_CONFIG_FIELDS } from '../../druid-models';
import { shallow } from '../../utils/shallow-renderer';

import { AutoForm } from './auto-form';

describe('AutoForm', () => {
  it('matches snapshot', () => {
    const autoForm = shallow(
      <AutoForm
        fields={[
          { name: 'testNumber', type: 'number' },
          { name: 'testSizeBytes', type: 'size-bytes' },
          { name: 'testString', type: 'string' },
          { name: 'testStringWithDefault', type: 'string', defaultValue: 'Hello World' },
          {
            name: 'testStringWithMultiline',
            type: 'string',
            multiline: true,
            defaultValue: 'Hello World',
          },
          { name: 'testBoolean', type: 'boolean' },
          { name: 'testBooleanWithDefault', type: 'boolean', defaultValue: false },
          { name: 'testStringArray', type: 'string-array' },
          {
            name: 'testStringArrayWithDefault',
            type: 'string-array',
            defaultValue: ['Hello', 'World'],
          },
          { name: 'testJson', type: 'json' },

          {
            name: 'testStringRequiredAndDefaultValue',
            type: 'string',
            defaultValue: 'hello',
            required: () => true,
          },

          { name: 'testNotDefined', type: 'string', defined: false },
          { name: 'testHide', type: 'string', hide: true },
          { name: 'testHideInMore', type: 'string', hideInMore: true },
        ]}
        model={String}
        onChange={() => {}}
      />,
    );
    expect(autoForm).toMatchSnapshot();
  });

  describe('.issueWithModel', () => {
    it('should find no issue when everything is fine', () => {
      expect(AutoForm.issueWithModel({}, COMPACTION_CONFIG_FIELDS)).toBeUndefined();

      expect(
        AutoForm.issueWithModel(
          {
            dataSource: 'ds',
            taskPriority: 25,
            maxRowsPerSegment: null,
            skipOffsetFromLatest: 'P4D',
            tuningConfig: {
              maxRowsInMemory: null,
              maxBytesInMemory: null,
              maxTotalRows: null,
              splitHintSpec: null,
              partitionsSpec: {
                type: 'dynamic',
                maxRowsPerSegment: 5000000,
                maxTotalRows: null,
              },
              indexSpec: null,
              indexSpecForIntermediatePersists: null,
              maxPendingPersists: null,
              pushTimeout: null,
              segmentWriteOutMediumFactory: null,
              maxNumConcurrentSubTasks: null,
              maxRetry: null,
              taskStatusCheckPeriodMs: null,
              chatHandlerTimeout: null,
              chatHandlerNumRetries: null,
              maxNumSegmentsToMerge: null,
              totalNumMergeTasks: null,
              type: 'index_parallel',
              forceGuaranteedRollup: false,
            },
            taskContext: null,
          },
          COMPACTION_CONFIG_FIELDS,
        ),
      ).toBeUndefined();
    });
  });

  it('should find issue correctly', () => {
    expect(AutoForm.issueWithModel(undefined as any, COMPACTION_CONFIG_FIELDS)).toEqual(
      'model is undefined',
    );

    expect(
      AutoForm.issueWithModel(
        {
          dataSource: 'ds',
          taskPriority: 25,
          skipOffsetFromLatest: 'P4D',
          tuningConfig: {
            partitionsSpec: {
              type: 'dynamic',
              maxRowsPerSegment: 5000000,
              maxTotalRows: null,
            },
            totalNumMergeTasks: 5,
            type: 'index_parallel',
            forceGuaranteedRollup: false,
          },
          taskContext: null,
        },
        COMPACTION_CONFIG_FIELDS,
      ),
    ).toEqual('field tuningConfig.totalNumMergeTasks is defined but it should not be');

    expect(
      AutoForm.issueWithModel(
        {
          dataSource: 'ds',
          taskPriority: 25,
          skipOffsetFromLatest: 'P4D',
          tuningConfig: {
            partitionsSpec: {
              type: 'not_a_know_partition_spec',
              maxRowsPerSegment: 5000000,
            },
            totalNumMergeTasks: 5,
            type: 'index_parallel',
            forceGuaranteedRollup: false,
          },
          taskContext: null,
        },
        COMPACTION_CONFIG_FIELDS,
      ),
    ).toBeUndefined();
  });
});
