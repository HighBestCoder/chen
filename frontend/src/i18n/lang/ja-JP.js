import el from 'element-ui/lib/locale/lang/ja'

const message = {
  instance: {
    "title": "インスタンスを選択 / 新しいコンソール",
    "help": "許可された MongoDB または Cosmos DB Mongo API インスタンスを選択します。新しいページで独立したコンソールを開き、データベースを選択できます。現在のクエリは保持されます。",
    "asset": "インスタンス",
    "account": "許可されたアカウント",
    "cancel": "キャンセル",
    "open": "新しいコンソールを開く",
    "empty": "No authorized MongoDB / Cosmos DB Mongo API instances available.",
    "no_account": "No account available for direct connection. Use the workbench if credentials or approval are required.",
    "instance_org_required": "Organization context is missing. Reopen from the JumpServer workbench.",
    "instance_login_required": "Sign in to the JumpServer workbench and retry.",
    "instance_denied": "Connection denied or not approved. Check permissions; use the workbench for approval.",
    "instance_failed": "Unable to load or connect. Retry and check permissions, login ACL and network.",
    "instance_popup": "Allow popups for this site and retry."
},
  title: {
    database_explorer: 'データベースエクスプローラ',
    save_sql: 'SQLを保存する',
    select_sql: 'SQLを選択する',
    export_data: 'データをエクスポートする'
  },
  button: {
    run: '実行',
    run_selected: '選択実行',
    refresh: '更新',
    total: '合計',
    insert: '挿入'
  },
  common: {
    total_unknown: '合計件数は不明',
    num_row: '{num}行',
    data_size: 'データ量',
    log: 'ログ出力',
    current: '現在',
    name: '名前',
    content: '内容'
  },
  action: {
    confirm: '確認',
    cancel: 'キャンセル'
  },
  tip: {
    run: '実行 (Ctrl + Enter)',
    stop: '停止 (Ctrl + Shift + C)',
    format: 'フォーマット (Ctrl + L)',
    open: '開く (Ctrl + R)',
    save: '保存 (Ctrl + S)'
  },
  option: {
    export_all: 'エクスポートすべて',
    export_current: 'エクスポート現在',
    export_selected: '選択行をエクスポート'
  },
  msg: {
    copy_not_allowed: 'You are not allowed to copy, please contact the administrator to open it!',
    paste_not_allowed: 'You are not allowed to paste, please contact the administrator to open it!'
  },
  message: {
    command_required: 'Command name and content are required',
    upload_sql_only: 'Only .sql files can be uploaded',
    upload_failed: 'Upload failed',
    save_success: '保存しました',
    command_sensitive: 'The command appears to contain a connection string, password, or token. Remove sensitive data before saving.'
  }
}
export default {
  ...el,
  ...message
}
