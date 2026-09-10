<template>
  <div v-loading="state.editorLoading " style="background-color: #383a3c;">

    <SelectSnippetDialog
        v-if="selectSnippetDialogVisible"
        :visible.sync="selectSnippetDialogVisible"
        @select="onSelectSnippets"
    />

    <SaveSnippetDialog
        v-if="saveSnippetDialogVisible"
        :content="statement"
        :visible.sync="saveSnippetDialogVisible"
    />
    <Toolbar
        :items="toolbarItems"
        :right-items="rightToolbarItems"
        style="margin-left: 5px"
    />

    <el-upload
        style="display: none"
        action="/chen/api/console/upload"
        :multiple="false"
        :with-credentials="true"
        :show-file-list="false"
        :headers="{token: store.getters.token}"
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :before-upload="onBeforeUpload"
        accept=".sql"
    >
      <a ref="upload" slot="trigger">upload</a>
    </el-upload>

    <codemirror
        ref="cmEditor"
        v-model="statement"
        v-loading="state.inQuery"
        :options="options"
        @ready="onCmReady"
        @changes="onCmChange"
    />
  </div>
</template>

<script>

import { format } from 'sql-formatter'
import store from '@/store'
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { CodeMirror } from 'vue-codemirror'

import 'codemirror/mode/sql/sql.js'
import 'codemirror/mode/javascript/javascript.js'
import 'codemirror/theme/eclipse.css'
import 'codemirror/theme/3024-night.css'
import 'codemirror/addon/display/autorefresh'
import 'codemirror/addon/hint/show-hint.css'
import 'codemirror/addon/hint/show-hint'
import 'codemirror/addon/hint/sql-hint'
import 'codemirror/addon/lint/lint'
import 'codemirror/addon/edit/closebrackets.js'
import 'codemirror/addon/edit/matchbrackets.js'
import { getHints } from '@/api/resource'
import { formatMongoCommand } from '@/utils/mongoFormatter'
import SelectSnippetDialog from '@/components/Main/Explore/QueryConsole/SelectSnippetDialog.vue'
import SaveSnippetDialog from '@/components/Main/Explore/QueryConsole/SaveSnippetDialog.vue'

const formatterMap = {
  'clickhouse': 'sql',
  'mariadb': 'mariadb',
  'mysql': 'mysql',
  'postgresql': 'postgresql',
  'oracle': 'plsql',
  'sqlserver': 'tsql',
  'db2': 'db2',
  'dameng': 'dameng',
  'mongodb': 'javascript'
}
const modeMap = {
  'clickhouse': 'text/x-sql',
  'mariadb': 'text/x-mariadb',
  'mysql': 'text/x-mysql',
  'postgresql': 'text/x-pgsql',
  'oracle': 'text/x-plsql',
  'sqlserver': 'text/x-mssql',
  'db2': 'text/x-sql',
  'dameng': 'text/x-sql',
  'mongodb': 'javascript'
}

// Keep in sync with the "mongo" hint list in MongoSqlHintsHandlerStub.java
const mongoKeywords = [
  'db', 'getCollection', 'find', 'findOne', 'countDocuments', 'distinct', 'skip', 'aggregate',
  'insertOne', 'insertMany', 'updateOne', 'updateMany', 'deleteOne', 'deleteMany', 'drop',
  'show dbs', 'show collections', 'use', 'limit', 'sort', 'ISODate'
]

export default {
  name: 'CodeEditor',
  components: {
    SaveSnippetDialog,
    SelectSnippetDialog,
    Toolbar
  },
  props: {
    nodeKey: {
      type: String,
      default: ''
    },
    state: {
      type: Object,
      default: () => ({})
    },
    subjects: {
      type: Object,
      default: () => {
      }
    }
  },
  data() {
    return {
      currentContext: '',
      hintRequest: 0,
      selectSnippetDialogVisible: false,
      saveSnippetDialogVisible: false,
      options: {
        autoRefresh: true,
        indentWithTabs: true,
        smartIndent: true,
        mode: modeMap[store.getters.profile?.dbType],
        theme: '3024-night',
        lineNumbers: true,
        line: true,
        matchBrackets: true,
        autoCloseBrackets: true,
        hintOptions: {
          completeSingle: false
        },
        extraKeys: {
          'Ctrl-L': (cm) => {
            this.onFormat()
          },
          'Ctrl-Enter': (cm) => {
            this.onRun()
          },
          'Ctrl-Shift-C': (cm) => {
            this.onStop()
          },
          'Ctrl-S': (cm) => {
            this.saveSnippetDialogVisible = true
          },
          'Ctrl-R': (cm) => {
            this.selectSnippetDialogVisible = true
          }
        }
      },
      cm: null,
      statement: '',
      toolbarItems: {
        run: {
          name: () => {
            if (this.cmInstance) {
              if (this.selectionValue.length > 0) {
                return this.$t('button.run_selected')
              } else {
                return this.$t('button.run')
              }
            }
          },
          type: 'button',
          icon: 'iconfont icon-chen-play text-primary',
          tip: this.$t('tip.run'),
          disabled: () => this.state.inQuery || this.state.disconnected,
          loading: () => {
            return this.state.inQuery
          },
          onClick: () => this.onRun()
        },
        stop: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-stop',
          tip: this.$t('tip.stop'),
          onClick: () => this.onStop(),
          disabled: () => !this.state.canCancel
        },
        format: {
          type: 'button',
          icon: 'iconfont icon-chen-m-geshihuawenzi',
          tip: this.$t('tip.format'),
          onClick: () => this.onFormat(),
          disabled: () => this.state.inQuery || this.state.disconnected
        },
        open: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-file-open',
          tip: this.$t('tip.open'),
          onClick: () => {
            this.selectSnippetDialogVisible = true
          },
          disabled: () => this.state.inQuery || this.state.disconnected
        },
        save: {
          type: 'button',
          icon: 'iconfont icon-chen-save',
          tip: this.$t('tip.save'),
          onClick: () => {
            this.saveSnippetDialogVisible = true
          },
          disabled: () => this.state.inQuery || this.state.disconnected
        }
      },
      rightToolbarItems: {
        selectContext: {
          type: 'dropdown',
          trigger: 'click',
          options: [],
          onCommand: (command) => {
            this.$emit('action', { action: 'change_current_context', data: command })
          },
          customDisplayContent: () => {
            return this.$tc('common.current') + ' Context: ' + this.state.currentContext
          }
        }
      }
    }
  },
  computed: {
    store() {
      return store
    },
    cmInstance() {
      return this.cm
    },
    selectionValue() {
      return this.cmInstance ? this.cmInstance.getSelection() : ''
    },
    autoComplete() {
      return !store.getters.disableautohash
    }

  },
  watch: {
    state(val) {
      if (val.currentContext && val.currentContext !== this.currentContext) {
        this.refreshHints(val.currentContext)
        this.currentContext = val.currentContext
      }
      this.rightToolbarItems.selectContext.options = val.contexts?.map((context) => {
        const label = context === val.currentContext ? '✔️' + context : context
        return { label: label, value: context }
      })
    }
  },
  beforeDestroy() { this.hintRequest++ },
  methods: {
    onSelectSnippets(snippet) {
      if (this.statement.length > 0) {
        this.statement += '\n'
      }
      this.statement += snippet
      this.selectSnippetDialogVisible = false
    },
    onRun() {
      if (this.state.inQuery || this.state.disconnected) return
      const sql = this.selectionValue || this.statement
      this.$emit('action', { action: 'run_sql', data: sql })
    },
    onStop() {
      if (!this.state.canCancel || this.state.disconnected) return
      this.$emit('action', { action: 'cancel' })
    },
    onFormat() {
      if (this.state.inQuery || this.state.disconnected) return
      if (store.getters.profile?.dbType === 'mongodb') {
        this.statement = formatMongoCommand(this.statement)
        return
      }
      const lang = formatterMap[store.getters.profile?.dbType]
      this.statement = format(this.statement, { language: lang })
    },
    onCmReady(cm) {
      this.cm = cm
    },
    onCmChange(cm, change) {
      const { text, origin } = change[0]
      if (origin === '+input' && text[0].trim()) {
        if (this.autoComplete) {
          cm.showHint({
            hint: store.getters.profile?.dbType === 'mongodb' ? this.mongoHint : CodeMirror.hint.sql,
            completeSingle: false
          })
        }
      }
    },
    mongoHint(cm) {
      const cursor = cm.getCursor()
      const token = cm.getTokenAt(cursor)
      const prefix = token.string || ''
      const tables = this.options.hintOptions.tables || {}
      const suggestions = new Set(mongoKeywords)
      Object.values(tables).forEach((items) => {
        if (Array.isArray(items)) {
          items.forEach((item) => suggestions.add(item))
        }
      })
      const list = Array.from(suggestions)
        .filter((item) => item.toLowerCase().includes(prefix.toLowerCase()))
        .sort()
      return {
        list,
        from: CodeMirror.Pos(cursor.line, token.start),
        to: CodeMirror.Pos(cursor.line, token.end)
      }
    },
    refreshHints(context) {
      const request = ++this.hintRequest
      this.options.hintOptions.tables = {}
      return getHints(this.nodeKey, context).then((res) => {
        if (request === this.hintRequest) this.options.hintOptions.tables = res
      }).catch(() => { /* request interceptor reports the failure; stale hints stay cleared */ })
    },
    onBeforeUpload(file) {
      this.state.inQuery = true
    },
    onUploadSuccess(resp, file, fileList) {
      this.$emit('action', { action: 'run_sql_file', data: resp.path })
    },
    onUploadError() {
      this.state.inQuery = false
    }
  }
}

</script>

<style lang="scss">
.vue-codemirror {
  height: 100%;
}

.cm-s-3024-night.CodeMirror {
  height: calc(100% - 28px);
  font-size: 14px;
  background: #2B2B2B !important;
  border-bottom: 1px solid #5F5F5F !important;
}

.CodeMirror-linenumbers {
  width: 52px !important;
  background: #313335 !important;
}

.cm-s-3024-night .CodeMirror-linenumber {
  color: #BBBBBB !important;
  text-align: left !important;
}
</style>
