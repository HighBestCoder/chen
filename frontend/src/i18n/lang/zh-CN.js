import el from 'element-ui/lib/locale/lang/zh-CN'

const message = {
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
