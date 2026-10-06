import { auth, getProfile } from '@/api/app'
import { Message } from 'element-ui'
import i18n, { getLanguage, setLanguage } from '@/i18n'

const guards = {}
function updateClipboardGuard(event, denied) {
  if (guards[event]) {
    document.removeEventListener(event, guards[event])
    delete guards[event]
  }
  if (denied) {
    guards[event] = (e) => {
      Message.error(i18n.t(`msg.${event}_not_allowed`))
      e.preventDefault()
      if (navigator.clipboard) navigator.clipboard.writeText('').catch(() => {})
    }
    document.addEventListener(event, guards[event])
  }
}

const state = {
  authenticated: false,
  token: '',
  lang: 'zh-CN',
  disableautohash: false,
  profile: {
    dbType: '',
    username: '',
    assetName: '',
    canCopy: false,
    canPaste: false
  }
}

const mutations = {
  DISABLE_AUTO_HASH: (state, disableautohash) => {
    state.disableautohash = disableautohash
  },
  AUTH: (state, authResponse) => {
    state.authenticated = true
    state.token = authResponse.token
    state.lang = authResponse.lang
    // Switch the running page too; only writing localStorage left this console in the previous
    // language while the next console opened in the new one (OBS-07).
    localStorage.setItem('chen_language', authResponse.lang)
    setLanguage(getLanguage())
  },
  PROFILE: (state, profile) => {
    state.profile = profile
    updateClipboardGuard('copy', !profile.canCopy)
    updateClipboardGuard('paste', !profile.canPaste)
  }
}

const actions = {
  loadProfile({ commit }) {
    getProfile().then(data => {
      commit('PROFILE', data)
    })
  },

  auth({ commit }, params) {
    return new Promise((resolve, reject) => {
      auth(params.token, params.disableautohash).then(response => {
        commit('AUTH', response)
        commit('DISABLE_AUTO_HASH', params.disableautohash)
        resolve(response.token)
      }).catch(error => {
        reject(error)
      })
    })
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
