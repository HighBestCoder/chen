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
      // A new execution clears the previous alert so an old error is not shown next to a fresh result.
      if (data.close) {
        clearTimeout(this.dismissTimer)
        this.opened = false
        return
      }
      this.title = data.title
      this.message = data.message
      this.type = data.type
      this.opened = true
      // Errors stay until the user closes them: a 5-second alert vanished while the stale result
      // stayed in front, so a failed refresh/limit change/export looked like success (DEF-37/DEF-40).
      this.closeDelay = data.closeDelay != null ? data.closeDelay : (data.type === 'error' ? 0 : 5)

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
