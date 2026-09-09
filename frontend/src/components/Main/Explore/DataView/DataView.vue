<template>
  <div v-loading="state.loading" class="data-view" id="doc">
    <ExportDataDialog :visible.sync="exportDataDialogVisible" @submit="onExportSubmit"/>
    <Toolbar :items="iToolBarItems"/>
    <HotTable
        ref="hostTable"
        :settings="hotSettings"
        class="hot-table"
    />
  </div>
</template>

<script>
import Toolbar from '@/framework/components/Toolbar/index.vue'
import { HotTable } from '@handsontable/vue'
import { Subject } from 'rxjs'
import ExportDataDialog from '@/components/Main/Explore/DataView/ExportDataDialog.vue'

export default {
  name: 'DataView',
  components: { ExportDataDialog, Toolbar, HotTable },
  props: {
    meta: {
      type: Object,
      default: () => ({})
    },
    data: {
      type: Object,
      default: () => ({})
    },
    messageSubject: {
      type: Subject,
      default: () => new Subject()
    },
    stateSubject: {
      type: Subject,
      default: () => new Subject()
    },
    updateSubject: {
      type: Subject,
      default: () => new Subject()
    },
    toolBarItems: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      exportDataDialogVisible: false,
      state: {
        limit: 0,
        total: 0,
        pinned: false,
        loading: false,
        paged: false
      },
      defaultToolBarItems: {
        first: {
          type: 'button',
          icon: 'iconfont icon-chen-first_page',
          onClick: this.onFirstPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        prev: {
          type: 'button',
          icon: 'iconfont icon-chen-icon_paging_left',
          onClick: this.onPrevPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        total: {
          type: 'text',
          hidden: () => {
            return this.state.paged
          },
          value: () => {
            return '共 ' + this.$t('common.num_row', { num: this.state.total }) + this.dataSizeSuffix()
          }
        },
        pagination: {
          type: 'dropdown',
          trigger: 'click',
          hidden: () => {
            return !this.state.paged
          },
          options: [
            {
              label: this.$t('common.num_row', { num: 50 }),
              value: 50
            },
            {
              label: this.$t('common.num_row', { num: 100 }),
              value: 100
            },
            {
              label: this.$t('common.num_row', { num: 500 }),
              value: 500
            },
            {
              label: this.$t('common.num_row', { num: 5000 }),
              value: 5000
            },
            {
              label: this.$t('common.num_row', { num: 50000 }),
              value: 50000
            }
          ],
          onCommand: (command) => {
            this.$emit('action', { action: 'change_limit', data: command })
          },
          customDisplayContent: () => {
            let content = ''
            if (this.isStatePaged) {
              content += this.$t('common.num_row', { num: this.state.limit }) + ' | ' + content
            }
            content += this.$tc('button.total') + this.$t('common.num_row', { num: this.state.total })
            content += this.dataSizeSuffix()
            return content
          }
        },
        next: {
          type: 'button',
          icon: 'iconfont icon-chen-icon_paging_right',
          onClick: this.onNextPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        last: {
          type: 'button',
          icon: 'iconfont icon-chen-last-page',
          onClick: this.onLastPage,
          hidden: () => {
            return !this.state.paged
          }
        },
        refresh: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-reload1',
          onClick: this.onRefresh
        },
        export: {
          split: true,
          type: 'button',
          icon: 'iconfont icon-chen-arrow-to-bottom',
          onClick: this.onExport
        }
      },
      hotSettings: {
        contextMenu: false,
        selectionMode: 'multiple',
        // multiColumnSorting: true,
        rowHeaders: true,
        wordWrap: false,
        colWidths: 200,
        colHeaders: [],
        columns: [],
        manualColumnResize: true,
        manualRowResize: true,
        viewportColumnRenderingOffset: 200, // 渲染列数
        viewportRowRenderingOffset: 200, // 渲染行数
        licenseKey: 'non-commercial-and-evaluation'
      },
      init: false
    }
  },
  computed: {
    isStatePaged() {
      return this.state.paged
    },
    iToolBarItems() {
      return Object.assign(this.defaultToolBarItems, this.toolBarItems)
    }
  },
  watch: {
    data() {
      const names = this.data.fields.map(field => field.name)
      const headers = this.hotSettings.colHeaders
      if (!this.init || names.length !== headers.length || names.some((name, i) => name !== headers[i])) {
        this.initTable()
      } else {
        this.reloadTable()
      }
    }
  },
  mounted() {
    this.stateSubject.subscribe((state) => {
      if (state.title === this.meta.title) {
        this.state = state
      }
    })
  },
  methods: {
    getState() {
      return this.state
    },
    // Contract 1.2.4: the returned data volume has to be shown, not only
    // written to the audit log. sizeBytes is -1 when the engine could not
    // measure it, in which case nothing is appended.
    dataSizeSuffix() {
      const bytes = this.state.sizeBytes
      if (bytes === undefined || bytes === null || bytes < 0) {
        return ''
      }
      const units = ['B', 'KB', 'MB', 'GB']
      let value = bytes
      let unit = 0
      while (value >= 1024 && unit < units.length - 1) {
        value /= 1024
        unit++
      }
      const shown = unit === 0 ? value : value.toFixed(1)
      return ' | ' + this.$tc('common.data_size') + ' ' + shown + ' ' + units[unit]
    },
    reloadTable() {
      const hotInstance = this.$refs.hostTable.hotInstance
      hotInstance.loadData(this.data.data)
    },
    initTable() {
      const headers = this.data.fields.map((item) => item.name)
      const columns = this.data.fields.map((item) => {
        return {
          // A BSON field/SQL alias may literally contain dots. String accessors
          // are treated as nested paths by Handsontable and lose such values.
          data: row => row[item.name],
          type: 'text',
          readOnly: true
        }
      })
      this.hotSettings.colHeaders = headers
      this.hotSettings.columns = columns
      const hotInstance = this.$refs.hostTable.hotInstance
      hotInstance.updateSettings(this.hotSettings, false)
      hotInstance.render()
      this.reloadTable()
      this.init = true
    },
    onNextPage() {
      this.$emit('action', { action: 'next_page' })
    },
    onPrevPage() {
      this.$emit('action', { action: 'prev_page' })
    },
    onFirstPage() {
      this.$emit('action', { action: 'first_page' })
    },
    onLastPage() {
      this.$emit('action', { action: 'last_page' })
    },
    onRefresh() {
      this.$emit('action', { action: 'refresh' })
    },
    onExport() {
      this.exportDataDialogVisible = true
    },
    onExportSubmit(scope) {
      this.exportDataDialogVisible = false
      if (scope === 'selected') {
        this.$emit('action', { action: 'export', data: { scope, rows: this.getSelectedRows() }})
        return
      }
      this.$emit('action', { action: 'export', data: scope })
    },
    getSelectedRows() {
      const hotInstance = this.$refs.hostTable.hotInstance
      const ranges = hotInstance.getSelectedRange() || []
      const selected = []
      const seen = new Set()
      ranges.forEach((range) => {
        const from = Math.min(range.from.row, range.to.row)
        const to = Math.max(range.from.row, range.to.row)
        for (let row = from; row <= to; row++) {
          if (row < 0 || row >= this.data.data.length || seen.has(row)) {
            continue
          }
          seen.add(row)
          selected.push(this.data.data[row])
        }
      })
      return selected
    }
  }

}
</script>

<style lang="scss" scoped>
.data-view {
  height: 100%;
  box-sizing: border-box;
  padding-bottom: 26px;
  background: #2B2B2B;
}
</style>
