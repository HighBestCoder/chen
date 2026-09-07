import el from 'element-ui/lib/locale/lang/ja'

const message = {
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
    stop: '停止 (Ctrl + C)',
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
    save_success: '保存しました',
    command_sensitive: 'コマンドに接続文字列、パスワード、またはトークンが含まれている可能性があります。機密情報を削除してから保存してください。'
  }
}
export default {
  ...el,
  ...message
}
