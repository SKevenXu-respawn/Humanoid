<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const SPEAKERS = [
  { value: 'zh_female_jiaochuannv_uranus_bigtts', label: '娇喘女声 2.0' },
  { value: 'zh_female_vv_uranus_bigtts', label: 'Vivi 2.0' },
  { value: 'zh_female_xiaohe_uranus_bigtts', label: '小何 2.0' },
  { value: 'zh_male_m191_uranus_bigtts', label: '云舟 2.0' },
  { value: 'zh_male_taocheng_uranus_bigtts', label: '小天 2.0' },
  { value: 'zh_male_liufei_uranus_bigtts', label: '刘飞 2.0' },
  { value: 'zh_female_sophie_uranus_bigtts', label: '魅力苏菲 2.0' },
  { value: 'zh_female_qingxinnvsheng_uranus_bigtts', label: '清新女声 2.0' },
  { value: 'zh_female_cancan_uranus_bigtts', label: '知性灿灿 2.0' },
  { value: 'zh_female_sajiaoxuemei_uranus_bigtts', label: '撒娇学妹 2.0' },
  { value: 'zh_female_tianmeixiaoyuan_uranus_bigtts', label: '甜美小源 2.0' },
  { value: 'zh_female_tianmeitaozi_uranus_bigtts', label: '甜美桃子 2.0' },
  { value: 'zh_female_shuangkuaisisi_uranus_bigtts', label: '爽快思思 2.0' },
  { value: 'zh_female_peiqi_uranus_bigtts', label: '佩奇猪 2.0' },
  { value: 'zh_female_linjianvhai_uranus_bigtts', label: '邻家女孩 2.0' },
  { value: 'zh_male_shaonianzixin_uranus_bigtts', label: '少年梓辛 2.0' },
  { value: 'zh_male_sunwukong_uranus_bigtts', label: '猴哥 2.0' },
  { value: 'zh_female_yingyujiaoxue_uranus_bigtts', label: 'Tina老师 2.0' },
  { value: 'zh_female_kefunvsheng_uranus_bigtts', label: '暖阳女声 2.0' },
  { value: 'zh_female_xiaoxue_uranus_bigtts', label: '儿童绘本 2.0' },
  { value: 'zh_male_dayi_uranus_bigtts', label: '大壹 2.0' },
  { value: 'zh_female_mizai_uranus_bigtts', label: '黑猫侦探社咪仔 2.0' },
  { value: 'zh_female_jitangnv_uranus_bigtts', label: '鸡汤女 2.0' },
  { value: 'zh_female_meilinvyou_uranus_bigtts', label: '魅力女友 2.0' },
  { value: 'zh_female_liuchangnv_uranus_bigtts', label: '流畅女声 2.0' },
  { value: 'zh_male_ruyayichen_uranus_bigtts', label: '儒雅逸辰 2.0' },
  { value: 'en_male_tim_uranus_bigtts', label: 'Tim (English)' },
  { value: 'en_female_dacey_uranus_bigtts', label: 'Dacey (English)' },
  { value: 'en_female_stokie_uranus_bigtts', label: 'Stokie (English)' }
]

const loading = ref(false)
const saving = ref(false)
const defaultSpeaker = ref('')
const form = reactive({ enabled: true, speaker: '' })

async function load() {
  loading.value = true
  try {
    const data = await api.voiceConfig()
    form.enabled = !!data.enabled
    form.speaker = data.speaker || data.defaultSpeaker || ''
    defaultSpeaker.value = data.defaultSpeaker || ''
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.speaker) {
    ElMessage.warning('请选择或填写音色 ID')
    return
  }
  saving.value = true
  try {
    const data = await api.saveVoiceConfig({ enabled: form.enabled, speaker: form.speaker })
    form.enabled = !!data.enabled
    form.speaker = data.speaker
    ElMessage.success('已保存，立即生效')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" style="max-width: 720px">
    <el-card shadow="never">
      <el-form label-width="110px">
        <el-form-item label="语音发送">
          <el-switch v-model="form.enabled" />
          <span class="empty" style="padding-left: 12px">
            关闭后主 AI 不再看到 sendVoice 工具，已发送的语音不受影响
          </span>
        </el-form-item>
        <el-form-item label="音色">
          <el-select
            v-model="form.speaker"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入音色 ID"
            style="width: 100%"
          >
            <el-option
              v-for="item in SPEAKERS"
              :key="item.value"
              :label="`${item.label}（${item.value}）`"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="load">刷新</el-button>
        </el-form-item>
      </el-form>
      <div class="empty" style="text-align: left; padding: 0">
        默认音色：{{ defaultSpeaker }}。音色必须属于豆包语音合成 2.0（seed-tts-2.0），
        且已在火山引擎控制台开通；配置保存后立即生效，无需重启。
      </div>
    </el-card>
  </div>
</template>
