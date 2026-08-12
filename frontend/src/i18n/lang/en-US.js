import el from 'element-ui/lib/locale/lang/en'

const message = {
  title: {
    database_explorer: 'Database Explorer',
    save_sql: 'Save SQL',
    select_sql: 'Select SQL',
    export_data: 'Export Data'
  },
  button: {
    run: 'Run',
    run_selected: 'Run selected',
    refresh: 'Refresh',
    total: 'Total',
    insert: 'Insert'
  },
  common: {
    num_row: '{num} rows',
    log: 'Log Output',
    current: 'Current',
    name: 'Name',
    content: 'Content'
  },
  action: {
    confirm: 'Confirm',
    cancel: 'Cancel'
  },
  tip: {
    run: 'Run (Ctrl + Enter)',
    stop: 'Stop (Ctrl + C)',
    format: 'Format (Ctrl + L)',
    open: 'Open (Ctrl + R)',
    save: 'Save (Ctrl + S)'
  },
  option: {
    export_all: 'Export all data',
    export_current: 'Export current page',
    export_selected: 'Export selected rows'
  },
  msg: {
    copy_not_allowed: 'You are not allowed to copy, please contact the administrator to open it!',
    paste_not_allowed: 'You are not allowed to paste, please contact the administrator to open it!'
  },
  message: {
    save_success: 'Saved successfully',
    command_sensitive: 'The command appears to contain a connection string, password, or token. Remove sensitive data before saving.'
  }
}

export default {
  ...el,
  ...message
}
