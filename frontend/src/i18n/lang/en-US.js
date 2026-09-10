import el from 'element-ui/lib/locale/lang/en'

const message = {
  instance: {
    "title": "Select instance / New console",
    "help": "Select an authorized MongoDB or Cosmos DB Mongo API instance. A new page opens with an independent console, where you can select a database. Your current query is preserved.",
    "asset": "Instance",
    "account": "Authorized account",
    "cancel": "Cancel",
    "open": "Open new console",
    "empty": "No authorized MongoDB / Cosmos DB Mongo API instances available.",
    "no_account": "No account available for direct connection. Use the workbench if credentials or approval are required.",
    "instance_org_required": "Organization context is missing. Reopen from the JumpServer workbench.",
    "instance_login_required": "Sign in to the JumpServer workbench and retry.",
    "instance_denied": "Connection denied or not approved. Check permissions; use the workbench for approval.",
    "instance_failed": "Unable to load or connect. Retry and check permissions, login ACL and network.",
    "instance_popup": "Allow popups for this site and retry."
},
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
    total_unknown: 'Total unknown',
    num_row: '{num} rows',
    data_size: 'Data size',
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
    stop: 'Stop (Ctrl + Shift + C)',
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
    command_required: 'Command name and content are required',
    save_success: 'Saved successfully',
    command_sensitive: 'The command appears to contain a connection string, password, or token. Remove sensitive data before saving.'
  }
}

export default {
  ...el,
  ...message
}
