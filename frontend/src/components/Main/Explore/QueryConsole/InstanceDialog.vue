<template>
  <el-dialog :title="$t('instance.title')" :visible="true" :close-on-click-modal="false" @close="$emit('close')">
    <p>{{ $t('instance.help') }}</p>
    <el-alert v-if="error" :title="$t('instance.' + error)" type="error" :closable="false" />
    <el-form label-position="top">
      <el-form-item :label="$t('instance.asset')">
        <el-select v-model="asset" filterable :loading="loading" :disabled="loading || submitting" @change="selectAsset">
          <el-option v-for="item in assets" :key="item.id" :value="item.id" :label="item.name + ' (' + item.address + ')'" />
        </el-select>
        <p v-if="!loading && !assets.length && !error">{{ $t('instance.empty') }}</p>
      </el-form-item>
      <el-form-item :label="$t('instance.account')">
        <el-select v-model="account" :loading="accountsLoading" :disabled="accountsLoading || submitting || !asset">
          <el-option v-for="item in accounts" :key="item.name" :value="item.name" :label="item.name + ' (' + item.username + ')'" />
        </el-select>
        <p v-if="asset && !accountsLoading && !accounts.length && !error">{{ $t('instance.no_account') }}</p>
      </el-form-item>
    </el-form>
    <span slot="footer">
      <el-button @click="$emit('close')">{{ $t('instance.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!account || accountsLoading || submitting" @click="connect">{{ $t('instance.open') }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import { coreContext, getInstances, getInstanceAccounts, createInstanceSession } from '@/api/instances'

export default {
  data() {
    return { context: null, assets: [], accounts: [], asset: '', account: '', error: '', loading: true, accountsLoading: false, submitting: false, requestId: 0, disposed: false }
  },
  async mounted() {
    try {
      this.context = coreContext()
      this.assets = await getInstances(this.context)
    } catch (e) { this.showError(e) } finally { this.loading = false }
  },
  beforeDestroy() { this.disposed = true; this.requestId++ },
  methods: {
    showError(e) {
      const known = ['instance_org_required', 'instance_login_required', 'instance_denied', 'instance_failed', 'instance_popup']
      this.error = known.includes(e.message) ? e.message : 'instance_failed'
    },
    async selectAsset() {
      const requestId = ++this.requestId
      this.account = ''; this.accounts = []; this.error = ''; this.accountsLoading = true
      try {
        const accounts = await getInstanceAccounts(this.asset, this.context)
        if (requestId === this.requestId) this.accounts = accounts
      } catch (e) { if (requestId === this.requestId) this.showError(e) } finally {
        if (requestId === this.requestId) this.accountsLoading = false
      }
    },
    async connect() {
      if (this.submitting || this.accountsLoading || !this.assets.some(a => a.id === this.asset) || !this.accounts.some(a => a.name === this.account)) return
      // Open during the click gesture; only navigate after Core authorizes the new session.
      const page = window.open('about:blank', '_blank')
      if (!page) { this.error = 'instance_popup'; return }
      page.opener = null
      this.submitting = true; this.error = ''
      try {
        const url = await createInstanceSession(this.asset, this.account, this.context)
        if (this.disposed || page.closed) { page.close(); return }
        page.location.replace(url)
        this.$emit('close')
      } catch (e) { page.close(); this.showError(e) } finally { this.submitting = false }
    }
  }
}
</script>
