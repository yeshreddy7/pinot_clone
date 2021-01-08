import Controller from '@ember/controller';
import { get, set, getProperties } from '@ember/object';
import { task } from 'ember-concurrency';

import { getAnomaliesByAlertId, getPerformanceStatsByAlertId } from 'thirdeye-frontend/utils/anomaly';
import { getDatePickerSpec } from 'thirdeye-frontend/utils/date-picker-utils';

import moment from 'moment';

export default Controller.extend({
  /** Internal States */

  /**
   * Tracks the initial time range passed by the route, after extracting from YAML
   * Default to 48 hours in milliseconds if nothing is passed.
   *
   * @private
   * @type {Number}
   */
  timeWindowSize: 172800000,
  /**
   * Tracks the start date in milliseconds for anomalies fetch
   *
   * @private
   * @type {Number}
   */
  startDate: undefined,
  /**
   * Tracks the end date in milliseconds for anomalies fetch
   *
   * @private
   * @type {Number}
   */
  endDate: undefined,
  /**
   * Tracks whether the anomalies are explored on root level
   *
   * @private
   * @type {Boolean}
   */
  rootLevel: true,

  /** Helper functions */

  /**
   * Set the end date and start date
   *
   * @private
   */
  initializeDates() {
    set(this, 'startDate', moment().subtract(this.timeWindowSize, 'milliseconds').startOf('day').valueOf());
    set(this, 'endDate', moment().add(1, 'day').startOf('day').valueOf());
  },

  /**
   * Fetch and set the initial state to be passed into the ember bootstrap's
   * date-range-picker component
   * Refer https://www.daterangepicker.com/ for all the options it can be configured with
   *
   * @private
   */
  initializeDatePicker() {
    const { startDate, endDate } = this;

    set(this, 'datePicker', getDatePickerSpec(startDate, endDate));
  },

  /**
   * Fetch all the anomalies for the alert in the calculated time range
   *
   * @private
   */
  fetchAnomaliesTask: task(function* () {
    try {
      const { alertId } = get(this, 'model');
      const { startDate, endDate } = this;

      this.set('anomalies', yield getAnomaliesByAlertId(alertId, startDate, endDate));
    } catch (reason) {
      window.console.error('Unable to fetch anomalies - ', reason);
    }
  }),

  /**
   * Fetch the performance stats for the alert in the calculated time range
   *
   * @private
   */
  fetchPerformanceStatsTask: task(function* () {
    try {
      const { alertId } = get(this, 'model');
      const { startDate, endDate } = this;

      this.set('performanceStats', yield getPerformanceStatsByAlertId(alertId, startDate, endDate));
    } catch (reason) {
      window.console.error('Unable to fetch performance stats - ', reason);
    }
  }),

  /**
   * Perform all the initializations. To be called by the associated route once its
   * model is ready with all the data.
   * Note:- These initializations cannot be done inside the native `init` hook of the controller
   * because the model would not be ready before the controller instantiation.
   *
   * @public
   */
  activate() {
    const { timeWindowSize } = getProperties(this.model, 'timeWindowSize');

    if (timeWindowSize) {
      set(this, 'timeWindowSize', timeWindowSize);
    }

    this.initializeDates();
    this.initializeDatePicker();

    this.get('fetchAnomaliesTask').perform();
    this.get('fetchPerformanceStatsTask').perform();
  },

  /** Event Handlers */
  actions: {
    /**
     * Function to perform a new fetch for anomalies when a new time
     * frame is selected by the user
     *
     * @param {String} start
     *   The new start date for the anomalies exploration
     * @param {String} end
     *   The new end date for the anomalies exploration
     */
    onRangeSelection(start, end) {
      set(this, 'startDate', moment(start).valueOf());
      set(this, 'endDate', moment(end).valueOf());

      set(this, 'datePicker.RANGE_START', start);
      set(this, 'datePicker.RANGE_END', end);

      this.fetchAnomaliesTask.perform();
    },

    /**
     * Function to set date picker state variable depending on breadcrumb state
     * We disable the ability to select dates if user is not on root level
     *
     * @param {Boolean} isRoot
     *   Tracks if we are working on root level of the tree
     */
    onBreadcrumbClick(isRoot = false) {
      this.set('rootLevel', isRoot);
    }
  }
});
