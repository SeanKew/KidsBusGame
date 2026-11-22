package com.seankew.kidsbusgame.guardian

import java.time.Instant
import java.time.ZoneId

/*
 * 防沉迷控制器（B1）。
 *
 * 原则：
 *  - 纯 Kotlin/JVM（仅用 java.time，minSdk 26 可用），不依赖 android.*，可直接 JUnit。
 *  - 只统计「前台」时间：onForeground()/onBackground() 之间才累计；用单调时钟累计，不受改系统时间影响。
 *  - 不自带线程/定时器：宿主在前台每 ~1s 调用 tick()。所有公开方法线程安全（内部同步）。
 *  - 落盘：前台每 persistIntervalMs（15–30s）一次；状态变化（锁定/解锁/延长/切后台）立即落盘。
 *  - 已知取舍：Session 间隔、每日重置、游玩时段、休息倒计时依赖墙钟，无法完全防篡改（见文末说明）。
 *
 * 状态流转：
 *   PLAYING ──剩余≤approachWindowMs──▶ APPROACHING ──reportAtTerminus()──▶ NIGHT(60s) ──▶ LOCKED
 *      │                                   │                                  ▲
 *      └──────剩余≤0（强制）────────────────┴──────────────────────────────────┘
 *   LOCKED ──SESSION: 休息≥sessionGapMs / DAILY: 次日 / WINDOW: 进入时段 / 家长延长──▶ PLAYING
 */

// ───────────────────────── 对外枚举（wire 值与 src/core/types.ts 一致）─────────────────────────

enum class GuardianPhase(val wire: String) {
    PLAYING("playing"),
    APPROACHING("approaching"),
    NIGHT("night"),
    LOCKED("locked"),
}

enum class LockReason(val wire: String) {
    SESSION("session"),
    DAILY("daily"),
    WINDOW("window"),
}

// ───────────────────────── 配置 ─────────────────────────

/** 游玩时段（本地时间，分钟 0..1439）。start>end 表示跨午夜（如 20:00–07:00）。 */
data class PlayWindow(val startMinuteOfDay: Int, val endMinuteOfDay: Int) {
    init {
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY) { "startMinuteOfDay out of range: $startMinuteOfDay" }
        require(endMinuteOfDay in 0 until MINUTES_PER_DAY) { "endMinuteOfDay out of range: $endMinuteOfDay" }
        require(startMinuteOfDay != endMinuteOfDay) { "play window must not be empty" }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

data class GuardianConfig(
    val sessionLimitMs: Long = 20 * 60_000L,
    val dailyLimitMs: Long = 60 * 60_000L,
    val nightModeMs: Long = 60_000L,
    val sessionGapMs: Long = 600_000L,
    val approachWindowMs: Long = 120_000L,
    /** null = 游玩时段功能关闭（默认） */
    val playWindow: PlayWindow? = null,
    /** 落盘间隔，限定 15–30s */
    val persistIntervalMs: Long = 20_000L,
) {
    init {
        require(sessionLimitMs > 0) { "sessionLimitMs must be > 0" }
        require(dailyLimitMs > 0) { "dailyLimitMs must be > 0" }
        require(nightModeMs > 0) { "nightModeMs must be > 0" }
        require(sessionGapMs > 0) { "sessionGapMs must be > 0" }
        require(approachWindowMs >= 0) { "approachWindowMs must be >= 0" }
        require(persistIntervalMs in 15_000L..30_000L) { "persistIntervalMs must be within 15s..30s" }
    }
}

// ───────────────────────── 持久化状态 ─────────────────────────

/**
 * 需要落盘的最小状态。夜间模式是瞬时表现，不落盘：锁定即落盘，冷启动直接进入 LOCKED。
 * 序列化方式由 GuardianStore 实现决定（B2：SharedPreferences）。
 */
data class GuardianState(
    val version: Int = CURRENT_VERSION,
    /** 本地日期 yyyy-MM-dd */
    val dayKey: String,
    val dailyUsedMs: Long = 0L,
    val sessionUsedMs: Long = 0L,
    /** 家长延长累计：每日（次日清零）/ 单次（新 Session 清零） */
    val dailyBonusMs: Long = 0L,
    val sessionBonusMs: Long = 0L,
    /** 游玩时段延长：在此墙钟时刻之前视为时段内 */
    val windowOverrideUntilWallMs: Long = 0L,
    val lockReason: LockReason? = null,
    val lockedAtWallMs: Long = 0L,
    /** 最近一次前台活跃的墙钟时间，用于判定 Session 间隔 */
    val lastActiveWallMs: Long = 0L,
) {
    /** 读盘后的状态校验：不合法则丢弃并重建，避免损坏数据导致崩溃或负时长。 */
    fun isValid(): Boolean =
        version == CURRENT_VERSION &&
            DAY_KEY_REGEX.matches(dayKey) &&
            dailyUsedMs >= 0 && sessionUsedMs >= 0 &&
            dailyBonusMs >= 0 && sessionBonusMs >= 0 &&
            windowOverrideUntilWallMs >= 0 && lockedAtWallMs >= 0 && lastActiveWallMs >= 0 &&
            (lockReason != null || lockedAtWallMs == 0L)

    companion object {
        const val CURRENT_VERSION = 1
        private val DAY_KEY_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

// ───────────────────────── 可注入依赖 ─────────────────────────

interface GuardianClock {
    /** 墙钟（epoch ms）：用于日期、游玩时段、Session 间隔、休息倒计时 */
    fun wallTimeMs(): Long

    /** 单调时钟（ms）：用于前台时长累计与夜间倒计时，不受用户改系统时间影响 */
    fun monotonicMs(): Long

    fun zoneId(): ZoneId
}

interface GuardianStore {
    /** 无数据返回 null；实现可抛异常，控制器会按「无数据」处理 */
    fun load(): GuardianState?

    /** 应快速返回（如 SharedPreferences.apply）；抛异常时控制器会保留状态并在下次重试 */
    fun save(state: GuardianState)
}

fun interface GuardianListener {
    /** phase 或 lockReason 变化时回调。在调用方线程、且在控制器内部锁之外触发。 */
    fun onChanged(snapshot: GuardianSnapshot)
}

/**
 * 默认时钟。单调时钟用 System.nanoTime（Android 上为 CLOCK_MONOTONIC，不含深度休眠时间；
 * 前台游玩不涉及深度休眠，可接受）。
 */
object SystemGuardianClock : GuardianClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

// ───────────────────────── 对外快照 ─────────────────────────

data class GuardianSnapshot(
    val phase: GuardianPhase,
    val lockReason: LockReason?,
    /** 距离当前生效限制的剩余前台时间；锁定/夜间为 0 */
    val remainingMs: Long,
    val nightRemainingMs: Long,
    /** 仅 SESSION 锁定时有值 */
    val restRemainingMs: Long?,
    val endedAtTerminus: Boolean,
    val sessionUsedMs: Long,
    val dailyUsedMs: Long,
    /** 最近一次落盘是否成功 */
    val persistOk: Boolean,
    val wallTimeMs: Long,
)

// ───────────────────────── 控制器 ─────────────────────────

class AddictionController(
    private val clock: GuardianClock,
    private val store: GuardianStore,
    config: GuardianConfig = GuardianConfig(),
    private val listener: GuardianListener? = null,
) {
    private val lock = Any()

    private var config: GuardianConfig = config
    private var state: GuardianState
    private var foreground = false
    private var phase = GuardianPhase.PLAYING
    private var lastAccrueMono = 0L
    private var lastPersistMono = 0L
    private var nightEndMono = 0L
    private var endedAtTerminus = false
    private var persistOk = true
    private var dirty = false

    init {
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        state = loadOrFresh(wall)
        rolloverDayIfNeeded(wall)
        evaluate(wall, mono)
        if (dirty) persist(wall, mono)
    }

    // ───── 生命周期 ─────

    fun onForeground(): GuardianSnapshot = mutate {
        if (foreground) return@mutate
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        foreground = true
        lastAccrueMono = mono
        lastPersistMono = mono
        rolloverDayIfNeeded(wall)
        // 未锁定且离开前台 ≥ sessionGapMs → 新 Session；锁定态由解锁条件处理
        if (state.lockReason == null && wall - state.lastActiveWallMs >= config.sessionGapMs) {
            startNewSession()
        }
        evaluate(wall, mono)
    }

    fun onBackground(): GuardianSnapshot = mutate {
        if (!foreground) return@mutate
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        advance(wall, mono) // 先结算最后一段前台时间
        foreground = false
        if (phase == GuardianPhase.NIGHT) phase = GuardianPhase.LOCKED // 夜间模式不跨后台
        state = state.copy(lastActiveWallMs = wall)
        dirty = true
    }

    // ───── 周期推进 ─────

    /** 宿主在前台每 ~1s 调用一次；返回最新快照。 */
    fun tick(): GuardianSnapshot = mutate {
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        advance(wall, mono)
        if (foreground && mono - lastPersistMono >= config.persistIntervalMs) persist(wall, mono)
    }

    fun snapshot(): GuardianSnapshot = synchronized(lock) { buildSnapshot() }

    // ───── 协议事件 ─────

    /** 对应 JS→原生 `session.atTerminus`。仅在 APPROACHING 阶段生效，其余阶段忽略。 */
    fun reportAtTerminus(): GuardianSnapshot = mutate {
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        advance(wall, mono)
        if (foreground && phase == GuardianPhase.APPROACHING) {
            lock(binding(wall).reason, wall, mono, withNight = true, atTerminus = true)
        }
    }

    /**
     * 家长通过 MathGate 后调用（本类不校验 MathGate，由调用方保证）。
     * 同时延长：单次时长、每日时长、游玩时段截止；并解除当前锁定。
     * @throws IllegalArgumentException 分钟数不在 1..[MAX_EXTENSION_MINUTES]
     */
    fun grantExtension(minutes: Int = DEFAULT_EXTENSION_MINUTES): GuardianSnapshot {
        require(minutes in 1..MAX_EXTENSION_MINUTES) {
            "extension minutes must be in 1..$MAX_EXTENSION_MINUTES, was $minutes"
        }
        return mutate {
            val wall = clock.wallTimeMs()
            val mono = clock.monotonicMs()
            advance(wall, mono)
            val extra = minutes * 60_000L
            val s = state
            val windowBase = maxOf(s.windowOverrideUntilWallMs, wall + naturalWindowRemainingMs(wall), wall)
            state = s.copy(
                sessionBonusMs = s.sessionBonusMs + extra,
                dailyBonusMs = s.dailyBonusMs + extra,
                windowOverrideUntilWallMs = windowBase + extra,
                lockReason = null,
                lockedAtWallMs = 0L,
            )
            dirty = true
            evaluate(wall, mono)
        }
    }

    /** 家长修改设置后调用（配置本身的合法性由 GuardianConfig 构造时校验）。 */
    fun updateConfig(newConfig: GuardianConfig): GuardianSnapshot = mutate {
        config = newConfig
        advance(clock.wallTimeMs(), clock.monotonicMs())
    }

    // ───── 内部：线程安全包装 + 变更通知 ─────

    private fun mutate(block: () -> Unit): GuardianSnapshot {
        val (snapshot, changed) = synchronized(lock) {
            val beforePhase = phase
            val beforeReason = state.lockReason
            block()
            if (dirty) persist(clock.wallTimeMs(), clock.monotonicMs())
            val snap = buildSnapshot()
            snap to (phase != beforePhase || state.lockReason != beforeReason)
        }
        if (changed) listener?.onChanged(snapshot)
        return snapshot
    }

    // ───── 内部：核心逻辑（均在 lock 内调用）─────

    /** 跨日重置 → 累计前台时间（封顶于剩余额度）→ 重新评估 Phase。 */
    private fun advance(wall: Long, mono: Long) {
        rolloverDayIfNeeded(wall)
        val delta = (mono - lastAccrueMono).coerceAtLeast(0L)
        lastAccrueMono = mono // 无论是否计时都推进，避免夜间/锁定/后台的时间被补记
        val counting = foreground && state.lockReason == null &&
            (phase == GuardianPhase.PLAYING || phase == GuardianPhase.APPROACHING)
        if (counting) {
            val counted = minOf(delta, binding(wall).remainingMs.coerceAtLeast(0L))
            if (counted > 0L) {
                state = state.copy(
                    sessionUsedMs = state.sessionUsedMs + counted,
                    dailyUsedMs = state.dailyUsedMs + counted,
                )
            }
        }
        evaluate(wall, mono)
    }

    private fun evaluate(wall: Long, mono: Long) {
        val locked = state.lockReason
        if (locked != null && canClearLock(locked, wall)) clearLock(wall)

        if (state.lockReason != null) {
            val inNight = phase == GuardianPhase.NIGHT && foreground && mono < nightEndMono
            phase = if (inNight) GuardianPhase.NIGHT else GuardianPhase.LOCKED
            return
        }

        val b = binding(wall)
        when {
            b.remainingMs <= 0L -> lock(
                b.reason, wall, mono,
                // 仅在前台游玩中到点才播放夜间模式；解锁后立刻再次锁定则直接 LOCKED，避免反复夜间
                withNight = foreground && (phase == GuardianPhase.PLAYING || phase == GuardianPhase.APPROACHING),
                atTerminus = false,
            )
            b.remainingMs <= config.approachWindowMs -> phase = GuardianPhase.APPROACHING
            else -> phase = GuardianPhase.PLAYING
        }
    }

    private fun lock(reason: LockReason, wall: Long, mono: Long, withNight: Boolean, atTerminus: Boolean) {
        val c = config
        var s = state
        // 提前到终点站结束时，把被放弃的剩余额度计为已用，使后续「延长 N 分钟」恰为 N 分钟
        s = when (reason) {
            LockReason.SESSION -> s.copy(sessionUsedMs = maxOf(s.sessionUsedMs, c.sessionLimitMs + s.sessionBonusMs))
            LockReason.DAILY -> s.copy(dailyUsedMs = maxOf(s.dailyUsedMs, c.dailyLimitMs + s.dailyBonusMs))
            LockReason.WINDOW -> s
        }
        state = s.copy(lockReason = reason, lockedAtWallMs = wall, lastActiveWallMs = wall)
        endedAtTerminus = atTerminus
        if (withNight) {
            phase = GuardianPhase.NIGHT
            nightEndMono = mono + c.nightModeMs
        } else {
            phase = GuardianPhase.LOCKED
        }
        dirty = true
    }

    private fun canClearLock(reason: LockReason, wall: Long): Boolean = when (reason) {
        LockReason.SESSION -> wall - state.lockedAtWallMs >= config.sessionGapMs
        LockReason.DAILY -> false // 次日由 rolloverDayIfNeeded 解除
        LockReason.WINDOW -> windowRemainingMs(wall) > 0L
    }

    private fun clearLock(wall: Long) {
        val rested = wall - state.lockedAtWallMs >= config.sessionGapMs
        state = state.copy(lockReason = null, lockedAtWallMs = 0L)
        if (rested) startNewSession() // 休息满间隔 → 新 Session
        dirty = true
    }

    private fun startNewSession() {
        state = state.copy(sessionUsedMs = 0L, sessionBonusMs = 0L)
        dirty = true
    }

    /**
     * 仅当本地日期「前进」才重置。系统时间被调回时不重置，避免用回拨时间刷新当日额度。
     * （yyyy-MM-dd 字符串的字典序即日期先后）
     */
    private fun rolloverDayIfNeeded(wall: Long) {
        val key = dayKey(wall)
        if (key <= state.dayKey) return
        state = state.copy(dayKey = key, dailyUsedMs = 0L, dailyBonusMs = 0L)
        dirty = true
        if (state.lockReason == LockReason.DAILY) clearLock(wall)
    }

    private data class Binding(val remainingMs: Long, val reason: LockReason)

    /** 当前生效的限制 = 单次/每日/游玩时段 三者中剩余最少的一个。平局优先级：DAILY > WINDOW > SESSION。 */
    private fun binding(wall: Long): Binding {
        val s = state
        val c = config
        var best = Binding(c.dailyLimitMs + s.dailyBonusMs - s.dailyUsedMs, LockReason.DAILY)
        val window = windowRemainingMs(wall)
        if (window < best.remainingMs) best = Binding(window, LockReason.WINDOW)
        val session = c.sessionLimitMs + s.sessionBonusMs - s.sessionUsedMs
        if (session < best.remainingMs) best = Binding(session, LockReason.SESSION)
        return best
    }

    /** 游玩时段剩余：时段关闭返回 Long.MAX_VALUE；含家长延长。 */
    private fun windowRemainingMs(wall: Long): Long {
        if (config.playWindow == null) return Long.MAX_VALUE
        val override = (state.windowOverrideUntilWallMs - wall).coerceAtLeast(0L)
        return maxOf(naturalWindowRemainingMs(wall), override)
    }

    /** 不含延长的时段剩余；不在时段内为 0。使用「当日毫秒数」计算（夏令时切换日有 ≤1h 误差，MVP 接受）。 */
    private fun naturalWindowRemainingMs(wall: Long): Long {
        val w = config.playWindow ?: return 0L
        val nowMs = Instant.ofEpochMilli(wall).atZone(clock.zoneId()).toLocalTime().toNanoOfDay() / 1_000_000L
        val start = w.startMinuteOfDay * 60_000L
        val end = w.endMinuteOfDay * 60_000L
        return if (start < end) {
            if (nowMs in start until end) end - nowMs else 0L
        } else {
            when {
                nowMs >= start -> DAY_MS - nowMs + end
                nowMs < end -> end - nowMs
                else -> 0L
            }
        }
    }

    private fun dayKey(wall: Long): String =
        Instant.ofEpochMilli(wall).atZone(clock.zoneId()).toLocalDate().toString()

    private fun loadOrFresh(wall: Long): GuardianState {
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            persistOk = false
            null
        }
        return if (loaded != null && loaded.isValid()) {
            loaded
        } else {
            // 数据缺失/损坏 → 重建。注意：这会让损坏当天的已用额度归零（见「剩余风险」）
            dirty = true
            GuardianState(dayKey = dayKey(wall), lastActiveWallMs = wall)
        }
    }

    private fun persist(wall: Long, mono: Long) {
        if (foreground) state = state.copy(lastActiveWallMs = wall)
        try {
            store.save(state)
            persistOk = true
            dirty = false
            lastPersistMono = mono
        } catch (e: Exception) {
            persistOk = false // 不中断游戏；dirty 保持，下一次 tick 重试
        }
    }

    private fun buildSnapshot(): GuardianSnapshot {
        val wall = clock.wallTimeMs()
        val mono = clock.monotonicMs()
        val s = state
        val reason = s.lockReason
        val remaining = if (reason == null) binding(wall).remainingMs.coerceAtLeast(0L) else 0L
        val night = if (phase == GuardianPhase.NIGHT) (nightEndMono - mono).coerceIn(0L, config.nightModeMs) else 0L
        val rest = if (reason == LockReason.SESSION) {
            (config.sessionGapMs - (wall - s.lockedAtWallMs)).coerceIn(0L, config.sessionGapMs)
        } else {
            null
        }
        return GuardianSnapshot(
            phase = phase,
            lockReason = reason,
            remainingMs = remaining,
            nightRemainingMs = night,
            restRemainingMs = rest,
            endedAtTerminus = endedAtTerminus && (phase == GuardianPhase.NIGHT || phase == GuardianPhase.LOCKED),
            sessionUsedMs = s.sessionUsedMs,
            dailyUsedMs = s.dailyUsedMs,
            persistOk = persistOk,
            wallTimeMs = wall,
        )
    }

    companion object {
        const val DEFAULT_EXTENSION_MINUTES = 15

        /** 【假设】单次延长上限 4 小时，防止误输入；如需调整只改此常量 */
        const val MAX_EXTENSION_MINUTES = 240

        private const val DAY_MS = 24 * 60 * 60_000L
    }
}

/*
 * 已知取舍（须写入对外说明）：
 *  1. 墙钟依赖：向前调整系统时间可刷新每日额度、缩短休息倒计时、改变游玩时段判定。
 *     已做的缓解仅有：①前台时长用单调时钟累计；②日期只在「前进」时重置（回拨不重置）。
 *  2. 应用数据被清除/卸载重装会重置全部额度，客户端无法防止。
 *  3. 存储损坏时重建为新状态（偏宽松），而非锁定——避免因数据故障把孩子/家长锁死。
 */
