import el from 'element-ui/lib/locale/lang/zh-TW'

const message = {
  instance: {
    "title": "选择实例 / 新建控制台",
    "help": "选择已授权的 MongoDB 或 Cosmos DB Mongo API 实例，在新页面打开独立控制台，再选择数据库。当前查询保留。",
    "asset": "实例",
    "account": "授权账号",
    "cancel": "取消",
    "open": "打开新控制台",
    "empty": "No authorized MongoDB / Cosmos DB Mongo API instances available.",
    "no_account": "No account available for direct connection. Use the workbench if credentials or approval are required.",
    "instance_org_required": "Organization context is missing. Reopen from the JumpServer workbench.",
    "instance_login_required": "Sign in to the JumpServer workbench and retry.",
    "instance_denied": "Connection denied or not approved. Check permissions; use the workbench for approval.",
    "instance_failed": "Unable to load or connect. Retry and check permissions, login ACL and network.",
    "instance_popup": "Allow popups for this site and retry."
},
  title: {
    database_explorer: '資料庫瀏覽器',
    save_sql: '保存SQL',
    select_sql: '選擇SQL',
    export_data: '導出數據'
  },
  button: {
    run: '運行',
    run_selected: '運行選中',
    refresh: '刷新',
    total: '共',
    insert: '插入'
  },
  common: {
    total_unknown: '總數未知',
    num_row: '{num}行',
    data_size: '數據量',
    log: '日誌輸出',
    current: '當前',
    name: '名稱',
    content: '內容'
  },
  action: {
    confirm: '確認',
    cancel: '取消'
  },
  tip: {
    upload: '運行SQL文件',
    run: '運行 (Ctrl + Enter)',
    stop: '停止 (Ctrl + Shift + C)',
    format: '格式化 (Ctrl + L)',
    open: '打開 (Ctrl + R)',
    save: '保存 (Ctrl + S)'
  },
  option: {
    export_all: '導出全部',
    export_current: '導出當前',
    export_selected: '導出選中'
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
