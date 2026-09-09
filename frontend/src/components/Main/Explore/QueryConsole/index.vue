<template>
  <div v-loading="state.loading" class="container">
    <div class="content">
      <SplitPane :default-percent="40" :min-percent="20" split="horizontal">
        <template slot="paneL">
          <CodeEditor
              :node-key="nodeKey"
              :state="state"
              :subjects="subjects"
              @action="onEditorAction"
              @run="onRunSql"
          />
        </template>
        <template slot="paneR">
          <div class="">
            <div class="message">
              <Message :subject="subjects.messageSubject"/>
            </div>
            <ResultBar
                :subjects="subjects"
                :states="resultStates"
                @closeDataView="onCloseDataView"
                @dataViewAction="onDataViewAction"
                @limitChange="onLimitChange"
            />
          </div>
        </template>
      </SplitPane>
    </div>
  </div>
</template>

<script>
import CodeEditor from '@/components/Main/Explore/QueryConsole/CodeEditor.vue'
import ResultBar from '@/components/Main/Explore/QueryConsole/ResultBar.vue'
import store from '@/store'
import { Subject } from 'rxjs'
import Message from '@/components/Main/Explore/Message.vue'
import SplitPane from 'vue-splitpane'

export default {
  components: { Message, ResultBar, CodeEditor, SplitPane },
  props: {
    tab: {
      type: Object,
      default: () => {}
    },
    nodeKey: {
      type: String,
      default: ''
    },
    globalMessageSubject: {
      type: Subject,
      default: () => new Subject()
    }
  },
  data() {
    return {
      heartBeatInterval: 0,
      resultStates: {},
      ws: null,
      state: {
        loading: false,
        inQuery: false,
        currentContext: '',
        contexts: [],
        timeout: 0
      },
      subjects: {
        messageSubject: new Subject(),
        logSubject: new Subject(),
        newResultSubject: new Subject(),
        updateResultSubject: new Subject(),
        deleteResultSubject: new Subject(),
        eventSubject: new Subject(),
        stateSubject: new Subject()
      }
    }
  },
  mounted() {
    this.initWs()
  },
  beforeDestroy() {
    clearInterval(this.heartBeatInterval)
    if (this.ws) {
      this.ws.onopen = this.ws.onmessage = this.ws.onclose = this.ws.onerror = null
      this.ws.close()
    }
  },

  methods: {
    initWs() {
      const token = store.getters.token
      const scheme = document.location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${scheme}://${window.location.host}/chen/ws/console`, token)
      ws.onmessage = (e) => {
        const msg = JSON.parse(e.data)
        this.handleWSMessage(msg)
      }
      ws.onopen = () => {
        const connect = {
          type: 'connect',
          data: {
            nodeKey: this.nodeKey,
            type: 'query'
          }
        }
        this.ws.send(JSON.stringify(connect))
      }
      ws.onclose = () => {
        clearInterval(this.heartBeatInterval)
        this.tab.loading = false
        this.state = { ...this.state, loading: false, inQuery: false, disconnected: true }
        Object.entries(this.resultStates).forEach(([title, state]) => this.$set(this.resultStates, title, { ...state, loading: false, disconnected: true }))
        this.subjects.messageSubject.next({ title: 'Connection closed', message: 'Reconnect to continue.', type: 'error' })
      }
      ws.onerror = ws.onclose
      this.ws = ws
    },
    handleWSMessage(pkt) {
      switch (pkt.type) {
        case 'pong':
          break
        case 'init':
          this.tab.title = pkt.data.title
          this.tab.loading = false
          this.startHeartBeat()
          break
        case 'log':
          this.subjects.logSubject.next(pkt.data)
          break
        case 'new_data_view':
          this.subjects.newResultSubject.next(pkt.data)
          break
        case 'update_data_view':
          this.subjects.updateResultSubject.next(pkt.data)
          break
        case 'close_data_view': {
          (Array.isArray(pkt.data) ? pkt.data : [pkt.data]).forEach(item => {
            this.$delete(this.resultStates, typeof item === 'string' ? item : item && item.sql)
          })
          this.subjects.deleteResultSubject.next(pkt.data)
          break
        }
        case 'message':
          this.subjects.messageSubject.next(pkt.data)
          break
        case 'query_console_action':
          this.subjects.eventSubject.next(pkt.data)
          break
        case 'update_state':
          if (pkt.data.title === this.tab.title) {
            this.state = pkt.data
          } else {
            this.$set(this.resultStates, pkt.data.title, pkt.data)
            this.subjects.stateSubject.next(pkt.data)
          }
          break
      }
    },
    startHeartBeat() {
      clearInterval(this.heartBeatInterval)
      this.heartBeatInterval = setInterval(() => {
        if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ type: 'ping' }))
      }, 1000 * 10)
    },
    onEditorAction(action) {
      if (this.state.disconnected) return
      this.ws.send(JSON.stringify({ type: 'query_console_action', data: action }))
    },
    onDataViewAction(action) {
      if (this.state.disconnected) return
      this.ws.send(JSON.stringify({ type: 'data_view_action', data: action }))
    },
    onRunSql(sql) {
      if (this.state.disconnected) return
      this.ws.send(JSON.stringify({ type: 'sql', data: sql }))
    },
    onCloseDataView(name) {
      if (this.state.disconnected) return
      this.$delete(this.resultStates, name)
      this.ws.send(JSON.stringify({ type: 'close_data_view', data: name }))
    },
    onLimitChange(limit) {
      if (this.state.disconnected) return
      this.ws.send(JSON.stringify({ type: 'limit', data: limit }))
    }
  }
}
</script>

<style lang="scss" scoped>
.container {
  text-align: left;
  height: calc(100vh - 30px);

  .content {
    height: 100%;
  }

  .message {
    height: 22px;
  }
}
</style>
