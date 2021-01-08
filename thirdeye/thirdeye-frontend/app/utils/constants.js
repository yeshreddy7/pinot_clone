// Share constant file
export const deleteProps = {
  method: 'delete',
  headers: { 'content-type': 'Application/Json' },
  credentials: 'include'
};

export const toastOptions = {
  timeOut: 10000
};

export const BREADCRUMB_TIME_DISPLAY_FORMAT = 'MMM D HH:mm';

export default {
  deleteProps,
  toastOptions,
  BREADCRUMB_TIME_DISPLAY_FORMAT
};
