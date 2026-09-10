import el from 'element-ui/lib/locale/lang/ja'

const message = {
  instance: {
    "title": "インスタンスを選択 / 新しいコンソール",
    "help": "許可された MongoDB または Cosmos DB Mongo API インスタンスを選択します。新しいページで独立したコンソールを開き、データベースを選択できます。現在のクエリは保持されます。",
    "asset": "インスタンス",
    "account": "許可されたアカウント",
    "cancel": "キャンセル",
    "open": "新しいコンソールを開く",
    "empty": "利用できる MongoDB / Cosmos DB Mongo API インスタンスがありません。",
    "no_account": "直接接続できるアカウントがありません。認証情報の入力や承認が必要な場合はワークベンチから接続してください。",
    "instance_org_required": "組織情報がありません。JumpServer ワークベンチから開き直してください。",
    "instance_login_required": "JumpServer ワークベンチにログインして再試行してください。",
    "instance_denied": "接続が拒否されたか、未承認です。権限を確認し、承認が必要な場合はワークベンチから接続してください。",
    "instance_failed": "読み込みまたは接続に失敗しました。権限、ログイン ACL、ネットワークを確認して再試行してください。",
    "instance_popup": "ポップアップを許可して再試行してください。"
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
    copy_not_allowed: 'コピーは許可されていません。管理者に連絡して開放してもらってください！',
    paste_not_allowed: '貼り付けは許可されていません。管理者に連絡して開放してもらってください！'
  },
  message: {
    command_required: 'コマンド名と内容を入力してください',
    save_success: '保存しました',
    command_sensitive: 'コマンドに接続文字列、パスワード、またはトークンが含まれている可能性があります。機密情報を削除してから保存してください。'
  }
}
export default {
  ...el,
  ...message
}
