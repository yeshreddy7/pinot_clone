import { mockData } from "thirdeye-frontend/mocks/compositeAnomalies";
import { module, test } from "qunit";
import {
  parseRoot,
  parseSubtree
} from "thirdeye-frontend/utils/anomalies-tree-parser";

module("Unit | Utility | Anomalies tree parser utils", function() {
  test("it parses root level parent anomalies correctly", function(assert) {
    const explorationId = 121;
    const { breadcrumbInfo, output } = parseRoot(explorationId, mockData);

    const expectedBreadcrumbInfo = {
      title: "Alert Anomalies",
      isRoot: true,
      id: 121
    };

    const expectedOutput = [
      {
        componentPath: "parent-anomalies",
        data: [
          {
            id: 1,
            startTime: 1599462000000,
            endTime: 1599721200000,
            feedback: null,
            details: {
              group_entity_one: 2,
              group_entity_two: 2,
              metric_one: 2,
              metric_two: 1,
              undefined: 1
            }
          }
        ],
        title: "Entity"
      }
    ];

    assert.deepEqual(breadcrumbInfo, expectedBreadcrumbInfo);
    assert.deepEqual(output, expectedOutput);
  });

  test("it drills down a composite anomaly correctly - level 1 drilldown example", function(assert) {
    const anomalyId = 1;
    const {
      breadcrumbInfo: { id, isRoot },
      output
    } = parseSubtree(anomalyId, mockData);

    const expectedOutput = [
      {
        componentPath: "entity-groups",
        title: "ENTITY:group_entity_one",
        data: [
          {
            id: 2,
            groupName: "groupConstituentOne",
            startTime: 1599462000000,
            endTime: 1599721200000,
            feedback: null,
            criticality: "6.189942819613212",
            current: 32,
            predicted: 33
          },
          {
            id: 5,
            groupName: "groupConstituentTwo",
            startTime: 1599462000000,
            endTime: 1599721200000,
            feedback: null,
            criticality: "6.189942819613212",
            current: 32,
            predicted: 33
          }
        ]
      },
      {
        componentPath: "entity-groups",
        title: "ENTITY:group_entity_two",
        data: [
          {
            id: 8,
            groupName: "groupConstituentOne",
            startTime: 1599462000000,
            endTime: 1599721200000,
            feedback: null,
            criticality: "6.189942819613212",
            current: 32,
            predicted: 33
          },
          {
            id: 11,
            groupName: "groupConstituentTwo",
            startTime: 1599462000000,
            endTime: 1599721200000,
            feedback: null,
            criticality: "6.189942819613212",
            current: 32,
            predicted: 33
          }
        ]
      },
      {
        componentPath: "entity-metrics",
        title: "Metric Anomalies",
        data: [
          {
            id: 15,
            startTime: 1599462000000,
            endTime: 1599462000000,
            metric: "metric_one",
            feedback: null,
            current: 32,
            predicted: 33
          },
          {
            id: 16,
            startTime: 1599462000000,
            endTime: 1599462000000,
            metric: "metric_one",
            feedback: null,
            current: 32,
            predicted: 33
          },
          {
            id: 17,
            startTime: 1599462000000,
            endTime: 1599462000000,
            metric: "metric_two",
            feedback: null,
            current: 32,
            predicted: 33
          }
        ]
      },
      {
        componentPath: "parent-anomalies",
        title: "Entity",
        data: [
          {
            id: 18,
            startTime: 1599462000000,
            endTime: 1599462000000,
            feedback: null,
            details: {
              metric_three: 1
            }
          }
        ]
      }
    ];

    //breadcrumb tests
    assert.equal(id, 1);
    assert.notEqual(
      isRoot,
      true,
      "Breadcrumb state should be indicating we are not doing root level parsing of the tree"
    );

    //output tests
    assert.deepEqual(output, expectedOutput);
  });

  test("it drills down an ananomaly grouped by anomaly summarize grouper correctly - level 2 drilldown example", function(assert) {
    const anomalyId = 8;
    const { breadcrumbInfo, output } = parseSubtree(anomalyId, mockData);

    const expectedBreadcrumbInfo = {
      title: "group_entity_two/groupConstituentOne",
      id: 8
    };

    const expectedOutput = [
      {
        componentPath: "entity-groups",
        title: "ENTITY:",
        data: [
          {
            id: 9,
            startTime: 1599462000000,
            endTime: 1599548400000,
            feedback: null,
            metric: "metric_four",
            dimensions: {
              feature_name: "groupConstituentOne#",
              feature_section: "groupConstituentOne",
              dimension_three: "True",
              use_case: "DESKTOP"
            },
            current: 32,
            predicted: 33
          },
          {
            id: 10,
            startTime: 1599462000000,
            endTime: 1599548400000,
            feedback: null,
            metric: "metric_four",
            dimensions: {
              feature_name: "groupConstituentOne#",
              feature_section: "groupConstituentOne",
              dimension_three: "True",
              use_case: "DESKTOP"
            },
            current: 32,
            predicted: 33
          }
        ]
      }
    ];

    assert.deepEqual(breadcrumbInfo, expectedBreadcrumbInfo);
    assert.deepEqual(output, expectedOutput);
  });

  test("it drills down a composite anomaly correctly - level 2 drilldown example (composite anomaly within a composite anomaly)", function(assert) {
    const anomalyId = 18;
    const {
      breadcrumbInfo: { id, isRoot },
      output
    } = parseSubtree(anomalyId, mockData);

    const expectedOutput = [
      {
        componentPath: "entity-metrics",
        title: "Metric Anomalies",
        data: [
          {
            id: 19,
            startTime: 1599462000000,
            endTime: 1599462000000,
            metric: "metric_three",
            feedback: null,
            current: 32,
            predicted: 33
          }
        ]
      }
    ];

    //breadcrumb tests
    assert.equal(id, 18);
    assert.notEqual(
      isRoot,
      true,
      "Breadcrumb state should be indicating we are not doing root level parsing of the tree"
    );

    //output tests
    assert.deepEqual(output, expectedOutput);
  });
});
