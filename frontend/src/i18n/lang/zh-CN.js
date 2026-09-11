import el from 'element-ui/lib/locale/lang/zh-CN'

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
    command_required: '请输入命令名称和内容',
    save_success: '保存成功',
    command_sensitive: '命令中疑似包含连接串、密码或 Token，请移除敏感信息后再保存'
  },
  msg: {
    copy_not_allowed: '不允许复制，请联系管理员开启！',
    paste_not_allowed: '不允许粘贴，请联系管理员开启！'
  }

}

export default {
  ...el,
  ...message
}
