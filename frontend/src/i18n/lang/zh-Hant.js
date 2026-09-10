import el from 'element-ui/lib/locale/lang/zh-TW'

const message = {
  instance: {
    "title": "选择实例 / 新建控制台",
    "help": "选择已授权的 MongoDB 或 Cosmos DB Mongo API 实例，在新页面打开独立控制台，再选择数据库。当前查询保留。",
    "asset": "实例",
    "account": "授权账号",
    "cancel": "取消",
    "open": "打开新控制台",
    "empty": "没有可用的授权 MongoDB / Cosmos DB Mongo API 实例。",
    "no_account": "没有可直接连接的授权账号。需要输入凭据或审批时，请从工作台连接。",
    "instance_org_required": "缺少组织上下文，请从 JumpServer 工作台重新进入。",
    "instance_login_required": "请先登录 JumpServer 工作台，再重试。",
    "instance_denied": "无权连接或连接尚未获准，请检查授权；需要审批时请从工作台连接。",
    "instance_failed": "无法加载或连接实例，请刷新重试；检查授权、登录 ACL 及网络。",
    "instance_popup": "浏览器阻止了新页面，请允许弹出窗口后重试。"
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
    command_required: '請輸入命令名稱和內容',
    save_success: '保存成功',
    command_sensitive: '命令中疑似包含連線字串、密碼或 Token，請移除敏感資訊後再保存'
  },
  msg: {
    copy_not_allowed: '不允許複製，請聯絡管理員開啟！',
    paste_not_allowed: '不允許黏貼，請聯絡管理員開啟！'
  }

}

export default {
  ...el,
  ...message
}
