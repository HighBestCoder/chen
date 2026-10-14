import el from 'element-ui/lib/locale/lang/zh-CN'

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
    database_explorer: '数据库浏览器',
    save_sql: '保存SQL',
    select_sql: '选择SQL',
    export_data: '导出数据'
  },
  button: {
    run: '运行',
    run_selected: '运行选中',
    refresh: '刷新',
    total: '共',
    insert: '插入'
  },
  common: {
    total_unknown: '总数未知',
    num_row: '{num}行',
    data_size: '数据量',
    log: '日志输出',
    current: '当前',
    name: '名称',
    content: '内容'
  },
  action: {
    confirm: '确认',
    cancel: '取消'
  },
  tip: {
    upload: '运行SQL文件',
    run: '运行 (Ctrl + Enter)',
    stop: '停止 (Ctrl + Shift + C)',
    format: '格式化 (Ctrl + L)',
    open: '打开 (Ctrl + R)',
    save: '保存 (Ctrl + S)'
  },
  option: {
    export_all: '导出全部',
    export_current: '导出当前',
    export_selected: '导出选中'
  },
  message: {
    command_required: 'Command name and content are required',
    upload_sql_only: 'Only .sql files can be uploaded',
    upload_failed: 'Upload failed',
    save_success: '保存成功',
    command_sensitive: 'The command appears to contain a connection string, password, or token. Remove sensitive data before saving.'
  },
  msg: {
    copy_not_allowed: 'You are not allowed to copy, please contact the administrator to open it!',
    paste_not_allowed: 'You are not allowed to paste, please contact the administrator to open it!'
  }

}

export default {
  ...el,
  ...message
}
