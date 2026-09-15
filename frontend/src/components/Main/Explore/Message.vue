<template>
  <el-alert
    v-if="opened"
    :description="message"
    :title="title"
    :type="type"
    class="query-message"
    @close="onClose"
  />
</template>

<script>
import { Subject } from 'rxjs'

export default {
  name: 'Message',
  props: {
    subject: {
      type: Subject,
      default: () => {
      }
    }
  },
  data() {
    return {
      opened: false,
      title: '',
      message: '',
      type: 'info',
      closeDelay: 5
    }
  },
  mounted() {
    this.subscription = this.subject.subscribe((data) => {
      this.title = data.title
      this.message = data.message
      this.type = data.type
      this.opened = true
      this.closeDelay = data.closeDelay == null ? 5 : data.closeDelay

      clearTimeout(this.dismissTimer)
      if (this.closeDelay > 0) {
        this.dismissTimer = setTimeout(() => {
          this.opened = false
        }, this.closeDelay * 1000)
      }
    })
  },
  beforeDestroy() {
    this.subscription.unsubscribe()
    clearTimeout(this.dismissTimer)
  },
  methods: {
    onClose() {
      this.opened = false
    }
  }

}
</script>

<style scoped>
</style>
