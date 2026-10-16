<template>
  <!--
    外太空场景装饰层：纯 CSS + 内联 SVG 绘制，零外链资源、零新增依赖。
    所有元素 pointer-events:none 且 z-index 低于登录卡片；动画只用 transform/opacity。
  -->
  <div
    ref="rootRef"
    class="fx-deco"
    :class="[stateClass, { 'is-lite': liteMode }]"
    aria-hidden="true"
  >
    <!-- 视差包装层：--depth 越大跟随指针位移越大（远/中/近景分层，见 .par 样式） -->
    <!-- ① 深空星野：3 层视差星空（radial-gradient 平铺，无大量 DOM 星点） -->
    <div class="par" style="--depth: 0.25"><div class="stars layer-far"></div></div>
    <div class="par" style="--depth: 0.45"><div class="stars layer-mid"></div></div>
    <div class="par" style="--depth: 0.72">
      <div class="stars layer-near"></div>
      <!-- 少量闪烁星点（16 个，仅 opacity/scale 呼吸） -->
      <span v-for="(s, i) in twinkleStars" :key="`t${i}`" class="twinkle-star" :style="s"></span>
    </div>
    <!-- 星云：大尺寸 radial-gradient + 缓慢呼吸缩放与旋转（不使用大范围 filter:blur） -->
    <div class="par" style="--depth: 0.35">
      <div class="nebula neb-a"></div>
      <div class="nebula neb-b"></div>
    </div>

    <!-- ② 星座连线（由既有神经网络改造：星点呼吸 + 连线流光） -->
    <svg class="fx-net" style="--depth: 0.4" viewBox="0 0 600 400" preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="netLink" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#4f9dff" />
          <stop offset="100%" stop-color="#a855f7" />
        </linearGradient>
        <radialGradient id="netNode" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#ffffff" />
          <stop offset="42%" stop-color="#cfe3ff" />
          <stop offset="100%" stop-color="#5aa2ff" />
        </radialGradient>
      </defs>
      <g class="net-links" fill="none" stroke="url(#netLink)" stroke-width="0.9">
        <path d="M120 96 L268 62" />
        <path d="M268 62 L430 118" />
        <path d="M120 96 L196 196" />
        <path d="M268 62 L196 196" />
        <path d="M268 62 L330 208" />
        <path d="M430 118 L330 208" />
        <path d="M430 118 L486 224" />
        <path d="M196 196 L330 208" />
        <path d="M196 196 L132 300" />
        <path d="M330 208 L486 224" />
        <path d="M330 208 L282 320" />
        <path d="M486 224 L438 342" />
        <path d="M132 300 L282 320" />
        <path d="M282 320 L438 342" />
      </g>
      <g class="net-nodes" fill="url(#netNode)">
        <circle class="net-node d0" cx="120" cy="96" r="5" />
        <circle class="net-node d1" cx="268" cy="62" r="4.5" />
        <circle class="net-node d2" cx="430" cy="118" r="5" />
        <circle class="net-node d1" cx="196" cy="196" r="4.5" />
        <circle class="net-node d0" cx="330" cy="208" r="6" />
        <circle class="net-node d2" cx="486" cy="224" r="4.5" />
        <circle class="net-node d0" cx="132" cy="300" r="4" />
        <circle class="net-node d2" cx="282" cy="320" r="5" />
        <circle class="net-node d1" cx="438" cy="342" r="4.5" />
      </g>
    </svg>

    <!-- ③ 流星（新增）：长周期内仅一小段掠过，带渐隐拖尾（周期与位置由索引推导） -->
    <div class="meteor-field">
      <span v-for="(m, i) in meteors" :key="`m${i}`" class="meteor" :style="m"></span>
    </div>

    <!-- ④ 跃迁光条：周期性快速掠过的超空间光条（≤6，同一方向错开延迟） -->
    <div class="warp-field">
      <span v-for="(w, i) in warps" :key="`w${i}`" class="warp-line" :style="w"></span>
    </div>

    <!-- ⑤ 行星系统：光环行星（径向渐变受光面 + 缓慢自转纹理 + rotateX 倾斜光环）+ 卫星轨道 -->
    <div class="planet-system" style="--depth: 0.82">
      <!-- 卫星轨道：旋转容器公转 + 反方向自转保持星体朝向（先于行星，形成绕行遮挡） -->
      <div class="orbit-wrap">
        <div class="orbit-line"></div>
        <div class="orbit-rotor">
          <div class="sat sat-a"><span class="sat-core"></span></div>
          <div class="sat sat-b"><span class="sat-core"></span></div>
        </div>
      </div>
      <!-- 光环：perspective + rotateX 倾斜的环形带（conic-gradient + mask 挖空成环） -->
      <div class="planet-3d">
        <div class="ring-tilt"><div class="ring"></div></div>
      </div>
      <!-- 行星本体：径向渐变模拟受光面，伪元素做纹理自转与晨昏线 -->
      <div class="planet">
        <span class="planet-surface"></span>
        <span class="planet-shade"></span>
      </div>
    </div>

    <!-- ⑥ 深空空间站（新增）：核心舱 + 桁架 + 双太阳能板（对日定向缓慢翻转）+ 整体漂浮 -->
    <div class="station" style="--depth: 0.62">
      <svg viewBox="0 0 160 96">
        <defs>
          <linearGradient id="stPanel" x1="0" y1="0" x2="1" y2="0.4">
            <stop offset="0%" stop-color="#2f6fd0" />
            <stop offset="50%" stop-color="#7fb6ff" />
            <stop offset="100%" stop-color="#1c3f86" />
          </linearGradient>
          <linearGradient id="stHull" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#dfe9ff" />
            <stop offset="55%" stop-color="#8fb0e0" />
            <stop offset="100%" stop-color="#33507f" />
          </linearGradient>
        </defs>
        <!-- 桁架与节点 -->
        <path d="M38 48 H122" stroke="rgba(170,200,255,.5)" stroke-width="2" />
        <path
          d="M52 43 V53 M68 43 V53 M92 43 V53 M108 43 V53"
          stroke="rgba(170,200,255,.32)"
          stroke-width="1.4"
        />
        <!-- 双太阳能板：scaleX 收放模拟对日翻转（origin 取贴桁架的一侧） -->
        <g class="st-panel st-panel-l">
          <rect
            x="8"
            y="34"
            width="30"
            height="28"
            rx="2"
            fill="url(#stPanel)"
            stroke="rgba(190,220,255,.5)"
            stroke-width="0.8"
          />
        </g>
        <g class="st-panel st-panel-r">
          <rect
            x="122"
            y="34"
            width="30"
            height="28"
            rx="2"
            fill="url(#stPanel)"
            stroke="rgba(190,220,255,.5)"
            stroke-width="0.8"
          />
        </g>
        <!-- 核心舱与对接口 -->
        <ellipse
          cx="80"
          cy="48"
          rx="22"
          ry="9"
          fill="url(#stHull)"
          stroke="rgba(200,225,255,.55)"
          stroke-width="0.8"
        />
        <circle cx="80" cy="48" r="3.4" fill="#eaf3ff" />
      </svg>
    </div>

    <!-- ⑦ 陨石带（新增）：左下远景碎石群，缓慢上飘 + 自转 -->
    <div class="belt" style="--depth: 0.9">
      <span v-for="(a, i) in asteroids" :key="`a${i}`" class="asteroid" :style="a"></span>
    </div>

    <!-- ⑧ 飞船：主飞船（内联 SVG 流线舰体 + 引擎尾焰）沿分段缓动曲线穿过画面 -->
    <div class="ship ship-main">
      <svg viewBox="0 0 132 56">
        <defs>
          <linearGradient id="shipHullMain" x1="0" y1="0" x2="1" y2="0.7">
            <stop offset="0%" stop-color="#9ecbff" />
            <stop offset="48%" stop-color="#3f7fe0" />
            <stop offset="100%" stop-color="#132a63" />
          </linearGradient>
          <linearGradient id="shipGlassMain" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#ffffff" stop-opacity="0.95" />
            <stop offset="100%" stop-color="#7fb6ff" stop-opacity="0.15" />
          </linearGradient>
        </defs>
        <path
          d="M6 28 C 30 15, 62 13, 96 21 L 128 28 L 96 35 C 62 43, 30 41, 6 28 Z"
          fill="url(#shipHullMain)"
          stroke="rgba(180,220,255,.55)"
          stroke-width="0.8"
        />
        <path d="M38 21 L74 19 L92 28 L74 37 L38 35 Z" fill="rgba(165,212,255,.2)" />
        <ellipse cx="98" cy="28" rx="15" ry="5.6" fill="url(#shipGlassMain)" />
        <circle cx="26" cy="28" r="3.2" fill="#e6f3ff" />
      </svg>
    </div>
    <!-- 副飞船：远景僚机，反向穿越、尺寸更小、透明度更低（视差层） -->
    <div class="ship ship-escort">
      <svg viewBox="0 0 132 56">
        <defs>
          <linearGradient id="shipHullEscort" x1="0" y1="0" x2="1" y2="0.7">
            <stop offset="0%" stop-color="#8fb6e8" />
            <stop offset="55%" stop-color="#2c5aa8" />
            <stop offset="100%" stop-color="#0e1e46" />
          </linearGradient>
        </defs>
        <path
          d="M6 28 C 30 16, 62 14, 96 22 L 126 28 L 96 34 C 62 42, 30 40, 6 28 Z"
          fill="url(#shipHullEscort)"
          stroke="rgba(150,190,255,.4)"
          stroke-width="0.8"
        />
        <ellipse cx="98" cy="28" rx="13" ry="5" fill="rgba(200,230,255,.35)" />
        <circle cx="26" cy="28" r="2.6" fill="#cfe6ff" />
      </svg>
    </div>

    <!-- ⑨ 保留既有科技感：3D 线框立方体 + 双数据环（环形流光） -->
    <div class="cube-wrap" style="--depth: 0.55">
      <div class="cube">
        <span class="face f-front"></span>
        <span class="face f-back"></span>
        <span class="face f-left"></span>
        <span class="face f-right"></span>
        <span class="face f-top"></span>
        <span class="face f-bottom"></span>
      </div>
      <span class="data-ring ring-a"></span>
      <span class="data-ring ring-b"></span>
    </div>

    <!-- ⑩ 保留既有扫描光带 + 漂浮粒子 -->
    <div class="fx-scanband"></div>
    <span v-for="(p, i) in particles" :key="`p${i}`" class="particle" :style="p"></span>

    <!-- 交互联动反馈层（默认透明，由 .is-* 状态类触发一次性动画） -->
    <div class="jump-flash"></div>
    <div class="error-tint"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/** 场景状态（由登录页驱动）：idle 常态 / submitting 跃迁充能 / success 跃迁爆发 / error 告警抖动。 */
const props = withDefaults(
  defineProps<{ state?: 'idle' | 'submitting' | 'success' | 'error' }>(),
  { state: 'idle' },
)

/** 状态类名：用 computed 取值而非解构 props，避免依赖 Vue 版本对 props 解构的响应式支持。 */
const stateClass = computed(() => `is-${props.state}`)

/**
 * 闪烁星点参数：位置/尺寸/节奏全部由索引推导，保证渲染稳定、无需随机数。
 */
const twinkleStars = Array.from({ length: 16 }, (_, i) => {
  const size = 1 + (i % 3)
  return {
    left: `${2 + ((i * 37) % 96)}%`,
    top: `${3 + ((i * 53) % 92)}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDelay: `${(i * 0.7) % 6}s`,
    animationDuration: `${2.6 + (i % 5) * 0.8}s`,
  }
})

/**
 * 跃迁光条参数（6 条）：同一探测方向、错开延迟，使光条周期性出现而非持续显示。
 */
const warps = Array.from({ length: 6 }, (_, i) => ({
  top: `${8 + i * 15}%`,
  width: `${14 + (i % 3) * 7}%`,
  animationDelay: `${i * 2}s`,
}))

/** 漂浮粒子参数（14 个）：位置/延迟/时长由索引推导，保持既有视觉。 */
const particles = Array.from({ length: 14 }, (_, i) => {
  const size = 2 + (i % 3)
  return {
    left: `${5 + ((i * 6.7) % 90)}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDelay: `${(i * 0.9) % 9}s`,
    animationDuration: `${13 + (i % 5) * 2.5}s`,
    opacity: `${0.18 + (i % 4) * 0.12}`,
  }
})

/**
 * 流星参数（3 条）：长周期内仅开头一小段可见，形成「偶发掠过」的惊喜感；
 * 位置/长度/节奏全部由索引推导（无随机数，保证每次渲染稳定）。
 */
const meteors = Array.from({ length: 3 }, (_, i) => ({
  top: `${4 + i * 19}%`,
  width: `${130 + i * 52}px`,
  animationDelay: `${i * 4.2}s`,
  animationDuration: `${12 + i * 5}s`,
}))

/** 陨石带参数（8 颗）：左下远景碎石，尺寸/起点/节奏由索引推导。 */
const asteroids = Array.from({ length: 8 }, (_, i) => {
  const size = 4 + (i % 4) * 2
  return {
    left: `${6 + ((i * 11) % 58)}%`,
    top: `${64 + ((i * 7) % 24)}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDelay: `${(i * 1.7) % 16}s`,
    animationDuration: `${26 + (i % 4) * 6}s`,
  }
})

/**
 * 低端设备精简模式：不追求极致观感，优先保证输入与滚动流畅。
 * 判定信号取 `hardwareConcurrency`（逻辑核心数）与 `deviceMemory`（Chromium 专有，单位 GB）；
 * 任一偏低即进入精简模式。`prefers-reduced-motion` 的机器同样按精简处理（其动画本已全关）。
 */
const liteMode = ref(false)

/** 是否为低端设备（核心数 ≤4，或设备内存 ≤4GB）。 */
function isLowEndDevice(): boolean {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return true
  const cores = navigator.hardwareConcurrency
  if (typeof cores === 'number' && cores > 0 && cores <= 4) return true
  // deviceMemory 非标准（仅 Chromium 系实现）：缺失时按「非低端」处理，避免误伤
  const memory = (navigator as Navigator & { deviceMemory?: number }).deviceMemory
  return typeof memory === 'number' && memory > 0 && memory <= 4
}

/** 视差根元素：CSS 变量写在它上面，供各层按 --depth 取值。 */
const rootRef = ref<HTMLElement | null>(null)
/** 最大位移（px）：过大反而抢视线。 */
const PARALLAX_MAX_PX = 16
/** 阻尼系数：越小越滞后，位移观感更「重」。 */
const PARALLAX_DAMPING = 0.12

let frameId = 0
let targetX = 0
let targetY = 0
let currentX = 0
let currentY = 0
let reduceMotion = false

/**
 * 逐帧把指针偏移（-1~1，相对屏幕中心）写入 CSS 变量 ``--par-x``/``--par-y``。
 *
 * 采用「写 CSS 变量」而非直接改 transform：各层（星野、星云等）自身已有 transform 动画，
 * 直接覆写会与之冲突；变量方案让视差与自身动画互不干扰。
 */
function paint(): void {
  frameId = 0
  currentX += (targetX - currentX) * PARALLAX_DAMPING
  currentY += (targetY - currentY) * PARALLAX_DAMPING
  const el = rootRef.value
  if (el) {
    el.style.setProperty('--par-x', `${(currentX * PARALLAX_MAX_PX).toFixed(2)}px`)
    el.style.setProperty('--par-y', `${(currentY * PARALLAX_MAX_PX).toFixed(2)}px`)
  }
  // 未收敛则继续下一帧；收敛后自然停止，避免空转耗电
  if (Math.abs(targetX - currentX) > 0.002 || Math.abs(targetY - currentY) > 0.002) {
    frameId = requestAnimationFrame(paint)
  }
}

function onPointerMove(event: PointerEvent): void {
  targetX = (event.clientX / window.innerWidth - 0.5) * 2
  targetY = (event.clientY / window.innerHeight - 0.5) * 2
  if (!frameId) {
    frameId = requestAnimationFrame(paint)
  }
}

onMounted(() => {
  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  liteMode.value = isLowEndDevice()
  // 减少动效或低端设备：不注册指针监听（省掉每帧合成与 8 个视差层的位移计算）
  if (reduceMotion || liteMode.value) return
  window.addEventListener('pointermove', onPointerMove, { passive: true })
})

onBeforeUnmount(() => {
  // 未注册监听时移除属安全空操作，无需再判断 reduceMotion
  window.removeEventListener('pointermove', onPointerMove)
  if (frameId) {
    cancelAnimationFrame(frameId)
    frameId = 0
  }
})
</script>

<style scoped>
/* 装饰层：绝对覆盖、裁剪溢出、不接收指针事件，z-index 低于表单 */
.fx-deco {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 0;
  /* --par-x/--par-y：指针视差偏移（JS 写入）；
     --flow：跃迁倍速，作为除数压缩各层 animation-duration（状态类改写） */
  --par-x: 0px;
  --par-y: 0px;
  --flow: 1;
}

/* ============ 指针视差分层 ============ */
/* 包装层（星野/星云等自身有 transform 动画的元素必须包在 .par 里，避免 transform 冲突） */
.par {
  position: absolute;
  inset: 0;
}

/* 位移 = 指针偏移 × 该层深度（--depth 由模板内联指定，缺省 0.5：越大越近、位移越明显） */
.par,
.fx-net,
.planet-system,
.cube-wrap,
.station,
.belt {
  transform: translate3d(
    calc(var(--par-x) * var(--depth, 0.5)),
    calc(var(--par-y) * var(--depth, 0.5)),
    0
  );
}

/* ============ 登录状态联动 ============ */
.fx-deco.is-submitting {
  /* 跃迁充能：星野/光条整体加速，制造「引擎蓄力」感 */
  --flow: 5;
}

.fx-deco.is-success {
  /* 跃迁爆发：极速前冲（配合 .jump-flash 一次性爆闪） */
  --flow: 16;
}

.fx-deco.is-success .jump-flash {
  animation: jump-flash 0.42s ease-out;
}

.fx-deco.is-error {
  animation: deco-shake 0.46s ease-in-out;
}

.fx-deco.is-error .error-tint {
  animation: error-tint 0.9s ease-out;
}

/* 交互反馈层：默认全透明，仅在对应状态类下播放一次性动画 */
.jump-flash,
.error-tint {
  position: absolute;
  inset: 0;
  opacity: 0;
}

.jump-flash {
  background: radial-gradient(
    circle at 50% 44%,
    rgba(255, 255, 255, 0.5),
    rgba(120, 180, 255, 0.26) 42%,
    transparent 72%
  );
}

.error-tint {
  background: radial-gradient(circle at 50% 50%, transparent 38%, rgba(255, 78, 100, 0.24) 100%);
}

@keyframes jump-flash {
  0% {
    opacity: 0;
  }
  35% {
    opacity: 1;
  }
  100% {
    opacity: 0;
  }
}

@keyframes error-tint {
  0% {
    opacity: 0;
  }
  22% {
    opacity: 0.9;
  }
  100% {
    opacity: 0;
  }
}

/* 告警抖动：作用于装饰根层（其子层各自持有 transform，互不干扰） */
@keyframes deco-shake {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  15% {
    transform: translate3d(-7px, 1px, 0);
  }
  32% {
    transform: translate3d(6px, -1px, 0);
  }
  50% {
    transform: translate3d(-4px, 1px, 0);
  }
  68% {
    transform: translate3d(3px, 0, 0);
  }
  84% {
    transform: translate3d(-2px, 0, 0);
  }
}

/* ============ ① 深空星野 ============ */
/* 三层星野用 radial-gradient 平铺成背景（非 DOM 星点，数量可控且廉价） */
.stars {
  position: absolute;
  /* 只向上/下各外扩一个瓦片：平铺位移最多一个瓦片，足以保证循环期间始终铺满视口。
     原写法 inset:-60%（图层达视口 2.2×2.2 倍）会白白占用大量栅格内存。 */
  inset: calc(var(--tile) * -1) 0;
  background-repeat: repeat;
  animation: star-drift linear infinite;
}

.layer-far {
  --tile: 200px;
  background-size: var(--tile);
  background-image:
    radial-gradient(1px 1px at 18px 40px, rgba(190, 214, 255, 0.55), transparent),
    radial-gradient(1px 1px at 96px 132px, rgba(150, 185, 255, 0.4), transparent),
    radial-gradient(1px 1px at 152px 68px, rgba(215, 232, 255, 0.5), transparent),
    radial-gradient(1px 1px at 54px 170px, rgba(180, 205, 255, 0.32), transparent),
    radial-gradient(1px 1px at 128px 108px, rgba(200, 220, 255, 0.3), transparent);
  opacity: 0.55;
  /* 除以 --flow：跃迁充能/爆发时星野加速，形成「前冲」感（下同） */
  animation-duration: calc(180s / var(--flow));
}

.layer-mid {
  --tile: 280px;
  background-size: var(--tile);
  background-image:
    radial-gradient(1.4px 1.4px at 40px 60px, rgba(205, 225, 255, 0.7), transparent),
    radial-gradient(1.4px 1.4px at 190px 96px, rgba(170, 200, 255, 0.55), transparent),
    radial-gradient(1.4px 1.4px at 110px 210px, rgba(220, 235, 255, 0.6), transparent),
    radial-gradient(1.4px 1.4px at 250px 180px, rgba(180, 205, 255, 0.45), transparent);
  opacity: 0.7;
  animation-duration: calc(120s / var(--flow));
}

.layer-near {
  --tile: 360px;
  background-size: var(--tile);
  background-image:
    radial-gradient(2px 2px at 70px 90px, rgba(235, 244, 255, 0.9), transparent),
    radial-gradient(1.8px 1.8px at 260px 150px, rgba(200, 220, 255, 0.75), transparent),
    radial-gradient(1.6px 1.6px at 160px 300px, rgba(220, 235, 255, 0.7), transparent);
  opacity: 0.85;
  animation-duration: calc(72s / var(--flow));
}

/* 位移一个瓦片尺寸即可无缝循环（平铺背景） */
@keyframes star-drift {
  to {
    transform: translate3d(0, var(--tile), 0);
  }
}

.twinkle-star {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, #ffffff, #bcd6ff);
  box-shadow: 0 0 6px rgba(170, 205, 255, 0.9);
  animation: twinkle linear infinite;
}

@keyframes twinkle {
  0%,
  100% {
    opacity: 0.15;
    transform: scale(0.7);
  }
  50% {
    opacity: 1;
    transform: scale(1.2);
  }
}

/* 星云：柔性径向渐变 + 呼吸缩放，避免大面积 filter:blur 与混合层开销 */
.nebula {
  position: absolute;
  border-radius: 50%;
  opacity: 0.5;
  /* 呼吸 + 缓慢自转，让星云有「流动」而非单纯缩放 */
  animation: nebula-breathe 36s ease-in-out infinite alternate;
}

.neb-a {
  width: 560px;
  height: 560px;
  left: -160px;
  top: -140px;
  background: radial-gradient(
    circle at 42% 45%,
    rgba(70, 110, 255, 0.32),
    rgba(120, 80, 220, 0.14) 42%,
    transparent 70%
  );
}

.neb-b {
  width: 460px;
  height: 460px;
  right: -140px;
  bottom: -120px;
  background: radial-gradient(
    circle at 55% 50%,
    rgba(150, 70, 220, 0.28),
    rgba(60, 120, 255, 0.12) 45%,
    transparent 72%
  );
  animation-duration: 48s;
  animation-direction: alternate-reverse;
}

@keyframes nebula-breathe {
  from {
    transform: rotate(0deg) scale(1);
    opacity: 0.4;
  }
  to {
    transform: rotate(22deg) scale(1.16);
    opacity: 0.6;
  }
}

/* ============ ② 星座连线 ============ */
.fx-net {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.5;
}

.net-links path {
  stroke-dasharray: 8 14;
  animation: dash-flow 2.8s linear infinite;
}

@keyframes dash-flow {
  to {
    /* 44 = 虚线周期(22)的整数倍，保证无缝循环 */
    stroke-dashoffset: -44;
  }
}

.net-node {
  transform-box: fill-box;
  transform-origin: center;
  animation: node-breathe 3.4s ease-in-out infinite;
}

.net-node.d1 {
  animation-delay: -1.1s;
}

.net-node.d2 {
  animation-delay: -2.2s;
}

@keyframes node-breathe {
  0%,
  100% {
    transform: scale(0.85);
    opacity: 0.55;
  }
  50% {
    transform: scale(1.3);
    opacity: 1;
  }
}

/* ============ ③ 流星 ============ */
/* 周期内仅开头一小段可见 + 缓入缓出：形成「偶发掠过」的惊喜感（而非持续干扰） */
.meteor-field {
  position: absolute;
  inset: 0;
}

.meteor {
  position: absolute;
  left: -32%;
  height: 2px;
  border-radius: 2px;
  /* 尾部透明、头部明亮：拖尾随运动方向拖在身后 */
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0) 0%,
    rgba(210, 234, 255, 0.7) 66%,
    rgba(255, 255, 255, 0.95) 100%
  );
  transform: rotate(18deg);
  opacity: 0;
  animation-name: meteor-fly;
  animation-timing-function: cubic-bezier(0.45, 0, 0.9, 0.55);
  animation-iteration-count: infinite;
}

@keyframes meteor-fly {
  0% {
    transform: translate3d(0, 0, 0) rotate(18deg) scaleX(0.5);
    opacity: 0;
  }
  5% {
    opacity: 0.95;
  }
  24% {
    transform: translate3d(150vw, 48vh, 0) rotate(18deg) scaleX(1.28);
    opacity: 0;
  }
  100% {
    transform: translate3d(150vw, 48vh, 0) rotate(18deg) scaleX(1.28);
    opacity: 0;
  }
}

/* ============ ④ 跃迁光条 ============ */
.warp-field {
  position: absolute;
  inset: 0;
  /* 统一方向：整层旋转 + 放大，避免角落露出 */
  transform: rotate(-16deg) scale(1.3);
}

.warp-line {
  position: absolute;
  left: -25%;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.9), transparent);
  mix-blend-mode: screen;
  opacity: 0;
  animation: warp linear infinite;
  /* 除以 --flow：充能/爆发时 6 条光条几乎连续掠过，强化跃迁感 */
  animation-duration: calc(10s / var(--flow));
}

/* 一个周期内仅在开头 10% 快速掠过，其余时间保持不可见 => 周期性出现 */
@keyframes warp {
  0% {
    transform: translate3d(0, 0, 0) scaleX(0.6);
    opacity: 0;
  }
  4% {
    opacity: 0.85;
  }
  10% {
    transform: translate3d(140vw, 0, 0) scaleX(1.2);
    opacity: 0;
  }
  100% {
    transform: translate3d(140vw, 0, 0) scaleX(1.2);
    opacity: 0;
  }
}

/* ============ ⑤ 行星系统 ============ */
.planet-system {
  position: absolute;
  right: 6%;
  bottom: 12%;
  width: 220px;
  height: 220px;
}

.planet {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  overflow: hidden;
  background: radial-gradient(
    circle at 34% 30%,
    #a9d6ff 0%,
    #5090f0 26%,
    #2b4fbf 55%,
    #14245e 78%,
    #0a1230 100%
  );
  box-shadow:
    inset -20px -16px 44px rgba(4, 8, 24, 0.7),
    0 0 46px rgba(90, 140, 255, 0.32);
}

/* 纹理自转：用 rotate 旋转带状纹理伪层模拟“行星自转” */
.planet-surface {
  position: absolute;
  inset: -14%;
  border-radius: 50%;
  background:
    repeating-linear-gradient(102deg, rgba(255, 255, 255, 0.06) 0 8px, transparent 8px 22px),
    radial-gradient(circle at 62% 66%, rgba(120, 90, 220, 0.28), transparent 55%);
  animation: planet-spin 64s linear infinite;
}

/* 晨昏线：右侧压暗，强化受光面立体感（静态） */
.planet-shade {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle at 32% 28%, transparent 42%, rgba(2, 6, 20, 0.6) 100%);
}

@keyframes planet-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 光环容器：透视 + 倾斜，让环形带呈横向椭圆（近大远小） */
.planet-3d {
  position: absolute;
  inset: -38% -75%;
  perspective: 640px;
}

.ring-tilt {
  position: absolute;
  inset: 0;
  transform: rotateX(74deg);
}

/* 环形带：conic-gradient 提供旋转流光，radial mask 挖空圆形成环 */
.ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: conic-gradient(
    from 0turn,
    rgba(160, 200, 255, 0) 0deg,
    rgba(190, 218, 255, 0.7) 58deg,
    rgba(160, 200, 255, 0.12) 140deg,
    rgba(205, 175, 255, 0.62) 250deg,
    rgba(160, 200, 255, 0) 360deg
  );
  -webkit-mask: radial-gradient(
    closest-side,
    transparent 58%,
    #000 62%,
    #000 84%,
    transparent 88%
  );
  mask: radial-gradient(closest-side, transparent 58%, #000 62%, #000 84%, transparent 88%);
  animation: ring-spin 40s linear infinite;
}

@keyframes ring-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 卫星轨道：整体 scaleY 压扁成椭圆；旋转容器公转，卫星内层反向自转保持朝向 */
.orbit-wrap {
  position: absolute;
  left: 50%;
  top: 50%;
  /* 用百分比（相对容器宽度）而非固定 px：行星容器在小屏缩放时轨道同步等比缩放
     （固定 px 会让「小屏保留行星」时轨道尺寸错位） */
  width: 209%;
  height: 209%;
  margin: -104.5% 0 0 -104.5%;
  transform: scaleY(0.42);
}

.orbit-line {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px dashed rgba(150, 190, 255, 0.22);
}

.orbit-rotor {
  position: absolute;
  inset: 0;
  animation: orbit-spin 28s linear infinite;
}

.sat {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  margin: -6px 0 0 -6px;
}

.sat-a {
  left: 100%;
}

.sat-b {
  left: 0;
}

.sat-core {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  /* 小卫星：一段高光弧 + 光晕，反向自转时朝向保持稳定可被观察到 */
  background: radial-gradient(circle at 35% 30%, #ffffff, #8fc3ff 55%, #3a6fb0 100%);
  box-shadow:
    0 0 9px rgba(140, 190, 255, 0.9),
    inset -2px -2px 4px rgba(0, 0, 0, 0.45);
  animation: orbit-spin-reverse 28s linear infinite;
}

@keyframes orbit-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 与公转同周期反向，抵消容器旋转以保持卫星朝向 */
@keyframes orbit-spin-reverse {
  to {
    transform: rotate(-360deg);
  }
}

/* ============ ⑥ 深空空间站 ============ */
.station {
  position: absolute;
  right: 15%;
  top: 13%;
  width: 168px;
  opacity: 0.78;
}

/* 漂浮动画放在 svg 上：.station 自身的 transform 已被视差规则占用 */
.station svg {
  display: block;
  width: 100%;
  height: auto;
  filter: drop-shadow(0 0 8px rgba(110, 170, 255, 0.45));
  animation: station-bob 44s ease-in-out infinite alternate;
}

@keyframes station-bob {
  from {
    transform: translate3d(0, 0, 0) rotate(-2.2deg);
  }
  to {
    transform: translate3d(12px, -18px, 0) rotate(2.2deg);
  }
}

/* 太阳能板：scaleX 收放模拟「对日定向翻转」（transform-box 让 origin 以自身盒为基准） */
.st-panel {
  transform-box: fill-box;
}

.st-panel-l {
  transform-origin: right center;
  animation: panel-turn 20s ease-in-out infinite alternate;
}

.st-panel-r {
  transform-origin: left center;
  animation: panel-turn 20s ease-in-out infinite alternate-reverse;
}

@keyframes panel-turn {
  from {
    transform: scaleX(0.55);
  }
  to {
    transform: scaleX(1);
  }
}

/* ============ ⑦ 陨石带 ============ */
.belt {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 62%;
  height: 42%;
}

.asteroid {
  position: absolute;
  border-radius: 44% 56% 48% 52% / 54% 46% 58% 42%;
  background: radial-gradient(circle at 34% 30%, #b6c6e4, #55627f 62%, #232c44 100%);
  box-shadow:
    0 0 7px rgba(120, 160, 220, 0.35),
    inset -2px -2px 4px rgba(0, 0, 0, 0.5);
  opacity: 0;
  animation-name: asteroid-drift;
  animation-timing-function: linear;
  animation-iteration-count: infinite;
}

@keyframes asteroid-drift {
  0% {
    transform: translate3d(0, 0, 0) rotate(0deg);
    opacity: 0;
  }
  14% {
    opacity: 0.8;
  }
  86% {
    opacity: 0.8;
  }
  100% {
    transform: translate3d(20vw, -32vh, 0) rotate(240deg);
    opacity: 0;
  }
}

/* ============ ⑧ 飞船 ============ */
.ship {
  position: absolute;
  left: 0;
  top: 0;
  opacity: 0;
  will-change: transform, opacity;
}

.ship svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
  transform-box: fill-box;
  transform-origin: center;
  filter: drop-shadow(0 0 6px rgba(90, 150, 255, 0.5));
  /* 机身轻微 roll：叠加在父层「轨迹切线旋转」之上，弱化「平面贴纸感」 */
  animation: ship-roll 9s ease-in-out infinite;
}

@keyframes ship-roll {
  0%,
  100% {
    transform: rotate(-3.2deg);
  }
  50% {
    transform: rotate(3.2deg);
  }
}

/* 副飞船 roll 周期更长、相位错开，避免两舰同频摆动 */
.ship-escort svg {
  animation-duration: 13s;
  animation-delay: -4s;
}

.ship-main {
  width: 132px;
  height: 56px;
  animation: ship-cruise 26s linear infinite;
}

.ship-escort {
  width: 84px;
  height: 36px;
  animation: ship-escort-cruise 34s linear infinite;
  animation-delay: 3s;
}

/*
 * 主飞船：左下切入 → 右上穿出，rotate 跟随轨迹切线。
 *
 * **电影感关键**：在每个关键帧内声明 `animation-timing-function`，它作用于「从该关键帧
 * 起始的那一段」，从而在单个 animation 里实现「起步加速 → 持续加速 → 巡航 → 减速滑出」；
 * 若只在元素上写一个缓动，整段轨迹会匀速，观感呆板。因此每个停靠点都同时给出 transform
 * 与 opacity（避免出现「只改 opacity 的关键帧」打断分段缓动的归属）。
 */
@keyframes ship-cruise {
  0% {
    transform: translate3d(-42vw, 34vh, 0) rotate(10deg);
    opacity: 0;
    animation-timing-function: cubic-bezier(0.35, 0, 0.7, 0.15); /* 起步加速 */
  }
  12% {
    transform: translate3d(-14vw, 24vh, 0) rotate(4deg);
    opacity: 1;
    animation-timing-function: cubic-bezier(0.34, 0.03, 0.55, 0.95); /* 持续加速 */
  }
  48% {
    transform: translate3d(46vw, 9vh, 0) rotate(-8deg);
    opacity: 1;
    animation-timing-function: cubic-bezier(0.3, 0.02, 0.45, 1); /* 巡航匀速段 */
  }
  82% {
    transform: translate3d(104vw, 22vh, 0) rotate(2deg);
    opacity: 1;
    animation-timing-function: cubic-bezier(0.45, 0, 0.75, 0.9); /* 减速滑出 */
  }
  100% {
    transform: translate3d(140vw, 36vh, 0) rotate(10deg);
    opacity: 0;
  }
}

/* 副飞船：自右向左、视差更慢，角度不同、透明度更低（同样分段缓动） */
@keyframes ship-escort-cruise {
  0% {
    transform: translate3d(124vw, 12vh, 0) rotate(198deg);
    opacity: 0;
    animation-timing-function: cubic-bezier(0.38, 0, 0.72, 0.2);
  }
  14% {
    transform: translate3d(104vw, 20vh, 0) rotate(192deg);
    opacity: 0.55;
    animation-timing-function: cubic-bezier(0.36, 0.03, 0.58, 0.96);
  }
  50% {
    transform: translate3d(56vw, 30vh, 0) rotate(186deg);
    opacity: 0.55;
    animation-timing-function: cubic-bezier(0.3, 0.02, 0.45, 1);
  }
  86% {
    transform: translate3d(6vw, 42vh, 0) rotate(176deg);
    opacity: 0.55;
    animation-timing-function: cubic-bezier(0.45, 0, 0.75, 0.9);
  }
  100% {
    transform: translate3d(-32vw, 48vh, 0) rotate(168deg);
    opacity: 0;
  }
}

/* 引擎尾焰（外焰）：长度随巡航段速度变化——与舰体同周期、同关键百分比，
   巡航段拉长、起步/滑出段收短，让「速度感」与位移同步（而非固定长度脉动）。 */
.ship::before {
  content: '';
  position: absolute;
  top: 50%;
  right: 100%;
  width: 74px;
  height: 13px;
  margin-top: -6.5px;
  transform-origin: right center;
  background: linear-gradient(
    90deg,
    rgba(120, 200, 255, 0) 0%,
    rgba(140, 210, 255, 0.7) 55%,
    rgba(200, 235, 255, 0.85) 100%
  );
  border-radius: 7px;
  animation: flame-thrust linear infinite;
  animation-duration: 26s; /* 与主舰巡航同周期（副舰在 .ship-escort::before 覆盖） */
}

@keyframes flame-thrust {
  0% {
    transform: scaleX(0.45);
    opacity: 0.45;
  }
  12% {
    transform: scaleX(0.62);
    opacity: 0.7;
  }
  48% {
    transform: scaleX(1.55); /* 巡航段最长 */
    opacity: 1;
  }
  82% {
    transform: scaleX(0.9);
    opacity: 0.8;
  }
  100% {
    transform: scaleX(0.45);
    opacity: 0.45;
  }
}

/* 引擎尾焰（内核）：窄而亮的快速闪烁（熔芯高频抖动），绘制在 ::after 以便压在外焰之上 */
.ship::after {
  content: '';
  position: absolute;
  top: 50%;
  right: 100%;
  width: 22px;
  height: 7px;
  margin-top: -3.5px;
  transform-origin: right center;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0) 0%, rgba(240, 250, 255, 0.95) 100%);
  border-radius: 4px;
  animation: core-flicker 0.18s steps(2, end) infinite alternate;
}

@keyframes core-flicker {
  from {
    transform: scaleX(0.8);
    opacity: 0.65;
  }
  to {
    transform: scaleX(1.3);
    opacity: 1;
  }
}

.ship-escort::before {
  width: 52px;
  height: 10px;
  margin-top: -5px;
  animation-duration: 34s; /* 与副舰巡航同周期 */
}

.ship-escort::after {
  width: 16px;
  height: 5.5px;
  margin-top: -2.75px;
}

/* ============ ⑨ 既有 3D 立方体 + 数据环 ============ */
.cube-wrap {
  position: absolute;
  left: 5%;
  top: 16%;
  width: 150px;
  height: 150px;
  perspective: 900px;
}

.cube {
  position: absolute;
  inset: 0;
  transform-style: preserve-3d;
  animation: cube-spin 22s linear infinite;
}

.face {
  position: absolute;
  inset: 0;
  border: 1px solid rgba(120, 180, 255, 0.55);
  background: rgba(79, 157, 255, 0.05);
  box-shadow: inset 0 0 26px rgba(90, 162, 255, 0.28);
}

.f-front {
  transform: translateZ(75px);
}

.f-back {
  transform: rotateY(180deg) translateZ(75px);
}

.f-left {
  transform: rotateY(-90deg) translateZ(75px);
}

.f-right {
  transform: rotateY(90deg) translateZ(75px);
}

.f-top {
  transform: rotateX(90deg) translateZ(75px);
}

.f-bottom {
  transform: rotateX(-90deg) translateZ(75px);
}

@keyframes cube-spin {
  from {
    transform: rotateX(-18deg) rotate3d(1, 1, 0, 0deg);
  }
  to {
    transform: rotateX(-18deg) rotate3d(1, 1, 0, 360deg);
  }
}

.data-ring {
  position: absolute;
  inset: -26px;
  border-radius: 50%;
  border: 1px solid rgba(120, 180, 255, 0.16);
  border-top-color: #4f9dff;
  will-change: transform;
  animation: ring-spin 6s linear infinite;
}

.ring-b {
  inset: -44px;
  border-top-color: #a855f7;
  border-color: rgba(168, 85, 247, 0.12);
  animation-duration: 9s;
  animation-direction: reverse;
}

/* ============ ⑩ 既有扫描光带 + 粒子 ============ */
.fx-scanband {
  position: absolute;
  left: -10%;
  right: -10%;
  top: 0;
  height: 26vh;
  /* 去掉 filter:blur(12px)：该光带面积大且每帧位移，模糊需反复重算。
     改用多段渐变自身完成柔和过渡——两端本就渐隐到 transparent，观感等同原模糊效果。 */
  background: linear-gradient(
    180deg,
    transparent 0%,
    rgba(90, 162, 255, 0.05) 26%,
    rgba(90, 162, 255, 0.12) 44%,
    rgba(168, 85, 247, 0.14) 56%,
    rgba(168, 85, 247, 0.05) 74%,
    transparent 100%
  );
  animation: scanband 14s linear infinite;
}

@keyframes scanband {
  0% {
    transform: translate3d(0, -30vh, 0);
  }
  100% {
    transform: translate3d(0, 120vh, 0);
  }
}

.particle {
  position: absolute;
  bottom: -12px;
  border-radius: 50%;
  background: radial-gradient(circle, #cfe3ff, #4f9dff);
  /* 不加 will-change：14 个粒子本就有无限动画会被自动提升，显式声明只会额外常驻合成层 */
  animation: particle-rise linear infinite;
}

@keyframes particle-rise {
  0% {
    transform: translate3d(0, 0, 0);
    opacity: 0;
  }
  12% {
    opacity: 1;
  }
  88% {
    opacity: 0.7;
  }
  100% {
    transform: translate3d(0, -110vh, 0);
    opacity: 0;
  }
}

/* ============ 低端设备精简模式（.is-lite：核心数 ≤4 / 设备内存 ≤4GB / 减少动效） ============ */
/* 关闭指针视差位移：低端设备上不值得为 8 个视差层承担合成与重算成本 */
.fx-deco.is-lite .par,
.fx-deco.is-lite .fx-net,
.fx-deco.is-lite .planet-system,
.fx-deco.is-lite .cube-wrap,
.fx-deco.is-lite .station,
.fx-deco.is-lite .belt {
  transform: none;
}

/* 去掉瞬态装饰与大面积扫描带：视觉损失小，每帧绘制收益明显 */
.fx-deco.is-lite .meteor-field,
.fx-deco.is-lite .fx-scanband {
  display: none;
}

/* 星点与粒子各只保留前 6 个（DOM 不变，仅退出绘制与动画） */
.fx-deco.is-lite .twinkle-star:nth-of-type(n + 7),
.fx-deco.is-lite .particle:nth-of-type(n + 7) {
  display: none;
}

/*
 * 小屏/平板适配：**不再一刀切 display:none**，而是缩放 + 推入角落 + 降透明度保留场景层次。
 * 卡片是毛玻璃（backdrop-filter），其下装饰会被虚化，故保留不影响可读性。
 */
@media (max-width: 1023px) {
  .planet-system {
    right: -4%;
    bottom: -1%;
    /* 用容器宽高缩放：内部轨道已改为百分比，会等比跟随 */
    width: 150px;
    height: 150px;
    opacity: 0.55;
  }

  /* 立方体各面用固定 translateZ，无法靠容器尺寸等比缩放，只能用 transform 缩放；
     代价是该断点下它不再跟随指针（低价值取舍），故此处用 `.fx-deco .cube-wrap`（0,2,0）
     以确保在精简模式（.is-lite 的 transform:none 同为 0,2,0）下仍按源序生效。 */
  .fx-deco .cube-wrap {
    left: 3%;
    top: 12%;
    transform: scale(0.7);
    opacity: 0.5;
  }

  .station {
    right: 3%;
    top: 5%;
    width: 104px;
    opacity: 0.5;
  }

  .belt {
    width: 78%;
  }
}

/* 手机竖屏：进一步缩小并更多推入角落，保证不遮挡卡片主体 */
@media (max-width: 767px) {
  .planet-system {
    right: -12%;
    bottom: -5%;
    width: 118px;
    height: 118px;
    opacity: 0.45;
  }

  .fx-deco .cube-wrap {
    left: -2%;
    top: 8%;
    transform: scale(0.56);
    opacity: 0.4;
  }

  .station {
    right: -2%;
    top: 3%;
    width: 76px;
    opacity: 0.4;
  }

  .belt {
    width: 62%;
    opacity: 0.7;
  }
}

/* ============ 无障碍：偏好减少动效时暂停全部装饰动画（保留静态场景） ============ */
@media (prefers-reduced-motion: reduce) {
  .stars,
  .nebula,
  .twinkle-star,
  .net-links path,
  .net-node,
  .warp-line,
  .meteor,
  .planet-surface,
  .ring,
  .orbit-rotor,
  .sat-core,
  .ship,
  .ship svg,
  .ship::before,
  .ship::after,
  .cube,
  .data-ring,
  .station svg,
  .st-panel,
  .asteroid,
  .fx-scanband,
  .particle {
    animation: none !important;
  }

  /* 依赖动画位移才可见的元素直接隐藏，避免停在画面随机位置 */
  .ship,
  .warp-line,
  .meteor,
  .asteroid,
  .fx-scanband,
  .particle {
    opacity: 0 !important;
  }

  /* 交互反馈（爆闪/抖动）属纯动效，减少动效时彻底关闭 */
  .fx-deco,
  .jump-flash,
  .error-tint {
    animation: none !important;
  }

  .jump-flash,
  .error-tint {
    opacity: 0 !important;
  }
}
</style>
