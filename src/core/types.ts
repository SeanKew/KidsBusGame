/**
 * KidsBusGame — 核心共享类型（B1）
 *
 * 内容：载具类型 / 碰撞等级 / 防沉迷 Phase / 原生↔JS 协议 v1（信封 + 消息 + 信封级校验）
 *
 * 设计约束（Ponytail 精简）：
 *  - 纯类型 + 少量常量与纯函数，无任何运行时依赖，可被 Node / WebView / 单测直接 import。
 *  - 不用 enum（避免打包产物差异），统一用 `as const` 对象 / 字面量联合。
 *  - 校验只做「信封级」：版本、id、type、payload 是否为对象。
 *    载荷字段级校验属于桥接层（B2+），这里不做，避免扩大范围。
 *
 * 【假设】CollisionLevel 的三档语义为本文件提出的默认约定（见下），尚未与仓库其他模块核对。
 */

// ───────────────────────── 载具 ─────────────────────────

export const VEHICLE_TYPES = ['bus', 'train'] as const;
export type VehicleType = (typeof VEHICLE_TYPES)[number];

export function isVehicleType(value: unknown): value is VehicleType {
  return typeof value === 'string' && (VEHICLE_TYPES as readonly string[]).includes(value);
}

// ───────────────────────── 碰撞等级 ─────────────────────────
// MVP 不引入物理引擎：只用简单的包围盒/区域判定，等级决定「判定后做什么」。
//   None    纯装饰，不参与判定
//   Trigger 不阻挡，仅触发事件（站台区、收集物、终点站区域）
//   Solid   阻挡载具（路障、建筑），由游戏逻辑直接拦停/回弹，对儿童保持「软碰撞」表现

export const CollisionLevel = {
  None: 0,
  Trigger: 1,
  Solid: 2,
} as const;
export type CollisionLevel = (typeof CollisionLevel)[keyof typeof CollisionLevel];

export function isCollisionLevel(value: unknown): value is CollisionLevel {
  return value === 0 || value === 1 || value === 2;
}

// ───────────────────────── 防沉迷（与 Kotlin 端 wire 值一一对应）─────────────────────────

/**
 * playing      正常游玩
 * approaching  距离限制不足 approachWindowMs（默认 120s），引导驶向终点站
 * night        终点站夜间模式（默认 60s）
 * locked       已锁定休息（session / daily / window）
 */
export const GUARDIAN_PHASES = ['playing', 'approaching', 'night', 'locked'] as const;
export type GuardianPhase = (typeof GUARDIAN_PHASES)[number];

/** session=单次时长/休息间隔；daily=每日时长；window=游玩时段之外 */
export const LOCK_REASONS = ['session', 'daily', 'window'] as const;
export type LockReason = (typeof LOCK_REASONS)[number];

// ───────────────────────── 协议 v1：信封 ─────────────────────────

export const PROTOCOL_VERSION = 1 as const;

/** id 最大长度，防止异常超长消息 */
export const MAX_ENVELOPE_ID_LENGTH = 64;

/**
 * 所有消息共用的信封。
 *  - id      发送方生成的唯一字符串
 *  - replyTo 响应类消息填写所回应请求的 id（gate.result → gate.request，speak.result → speak）
 */
export interface Envelope<T extends string = string, P = unknown> {
  readonly v: typeof PROTOCOL_VERSION;
  readonly id: string;
  readonly type: T;
  readonly payload: P;
  readonly replyTo?: string;
}

// ───────────────────────── JS → 原生 ─────────────────────────

export interface HelloPayload {
  readonly protocol: typeof PROTOCOL_VERSION;
  /** JS 包构建标识（可选，仅用于排查） */
  readonly build?: string;
}

export interface ErrorPayload {
  readonly code: string;
  readonly message: string;
  /** true 表示 JS 侧无法继续，原生可选择重载页面 */
  readonly fatal: boolean;
  /** 可选；不得包含任何个人信息 */
  readonly stack?: string;
}

export interface SpeakPayload {
  readonly text: string;
  /** BCP-47，例如 'zh-CN'；缺省使用系统语言 */
  readonly lang?: string;
  /** true 则打断当前朗读 */
  readonly interrupt?: boolean;
}

/** 【假设】目前仅两种家长门用途：延长游玩、进入设置 */
export type GateRequestPurpose = 'extend' | 'settings';

export interface GateRequestPayload {
  readonly purpose: GateRequestPurpose;
}

export interface AtTerminusPayload {
  readonly stationId?: string;
}

/** 仅 debug 构建发送；release 原生端应丢弃（见 DEBUG_ONLY_TYPES） */
export interface PerfSamplePayload {
  readonly fps: number;
  readonly frameMsP95?: number;
  readonly heapMb?: number;
  readonly drawCalls?: number;
}

export interface JsToNativePayloads {
  readonly hello: HelloPayload;
  readonly error: ErrorPayload;
  readonly speak: SpeakPayload;
  readonly 'gate.request': GateRequestPayload;
  readonly 'session.atTerminus': AtTerminusPayload;
  readonly 'perf.sample': PerfSamplePayload;
}
export type JsToNativeType = keyof JsToNativePayloads;
export type JsToNativeMessage = {
  [K in JsToNativeType]: Envelope<K, JsToNativePayloads[K]>;
}[JsToNativeType];

// ───────────────────────── 原生 → JS ─────────────────────────

/** 防沉迷状态快照（JS 侧只需展示所需字段，用量统计不下发） */
export interface SessionStatePayload {
  readonly phase: GuardianPhase;
  readonly lockReason: LockReason | null;
  /** 距离本轮限制的剩余前台时间；night/locked 时为 0 */
  readonly remainingMs: number;
  /** 仅 night 阶段有意义 */
  readonly nightRemainingMs: number;
  /** 仅 lockReason==='session' 时为休息剩余时间，其余为 null */
  readonly restRemainingMs: number | null;
  /** night 阶段：true=已到终点站；false=到点强制进入 */
  readonly endedAtTerminus: boolean;
}

export interface InitPayload {
  readonly protocol: typeof PROTOCOL_VERSION;
  readonly appVersion: string;
  readonly debug: boolean;
  readonly locale: string;
  readonly session: SessionStatePayload;
}

/** replyTo = 对应 gate.request 的 id */
export interface GateResultPayload {
  readonly purpose: GateRequestPurpose;
  readonly ok: boolean;
  /** purpose==='extend' 且 ok 时，家长选择的延长分钟数 */
  readonly extendedMinutes?: number;
}

export type SpeakError = 'unavailable' | 'unsupported_lang' | 'failed';

/** replyTo = 对应 speak 的 id */
export interface SpeakResultPayload {
  readonly ok: boolean;
  readonly error?: SpeakError;
}

export interface LifecyclePayload {
  readonly state: 'foreground' | 'background';
}

export interface NativeToJsPayloads {
  readonly init: InitPayload;
  readonly 'session.state': SessionStatePayload;
  readonly 'gate.result': GateResultPayload;
  readonly 'speak.result': SpeakResultPayload;
  readonly lifecycle: LifecyclePayload;
}
export type NativeToJsType = keyof NativeToJsPayloads;
export type NativeToJsMessage = {
  [K in NativeToJsType]: Envelope<K, NativeToJsPayloads[K]>;
}[NativeToJsType];

// ───────────────────────── 运行时类型表（Record 保证与上面的键穷尽一致）─────────────────────────

const JS_TO_NATIVE: Readonly<Record<JsToNativeType, true>> = {
  hello: true,
  error: true,
  speak: true,
  'gate.request': true,
  'session.atTerminus': true,
  'perf.sample': true,
};

const NATIVE_TO_JS: Readonly<Record<NativeToJsType, true>> = {
  init: true,
  'session.state': true,
  'gate.result': true,
  'speak.result': true,
  lifecycle: true,
};

export const JS_TO_NATIVE_TYPES = Object.keys(JS_TO_NATIVE) as JsToNativeType[];
export const NATIVE_TO_JS_TYPES = Object.keys(NATIVE_TO_JS) as NativeToJsType[];

/** release 构建中原生端应直接丢弃的 JS→原生 消息类型 */
export const DEBUG_ONLY_TYPES: readonly JsToNativeType[] = ['perf.sample'];

export function isJsToNativeType(value: unknown): value is JsToNativeType {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(JS_TO_NATIVE, value);
}

export function isNativeToJsType(value: unknown): value is NativeToJsType {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(NATIVE_TO_JS, value);
}

// ───────────────────────── 信封级解析 ─────────────────────────

export type EnvelopeParseError =
  | 'not_object'
  | 'bad_version'
  | 'bad_id'
  | 'bad_type'
  | 'bad_payload'
  | 'bad_reply_to';

export type ParseResult<M> =
  | { readonly ok: true; readonly message: M }
  | { readonly ok: false; readonly error: EnvelopeParseError };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseEnvelope<M>(raw: unknown, isKnownType: (t: unknown) => boolean): ParseResult<M> {
  if (!isRecord(raw)) return { ok: false, error: 'not_object' };
  if (raw.v !== PROTOCOL_VERSION) return { ok: false, error: 'bad_version' };
  if (typeof raw.id !== 'string' || raw.id.length === 0 || raw.id.length > MAX_ENVELOPE_ID_LENGTH) {
    return { ok: false, error: 'bad_id' };
  }
  if (!isKnownType(raw.type)) return { ok: false, error: 'bad_type' };
  if (!isRecord(raw.payload)) return { ok: false, error: 'bad_payload' };
  if (raw.replyTo !== undefined && (typeof raw.replyTo !== 'string' || raw.replyTo.length === 0)) {
    return { ok: false, error: 'bad_reply_to' };
  }
  // 信封级校验通过；载荷字段级校验由桥接层负责
  return { ok: true, message: raw as unknown as M };
}

/** 原生端收到 JS 消息（通常已是 JSON.parse 后的对象）时使用 */
export function parseJsToNative(raw: unknown): ParseResult<JsToNativeMessage> {
  return parseEnvelope<JsToNativeMessage>(raw, isJsToNativeType);
}

/** JS 端收到原生消息时使用 */
export function parseNativeToJs(raw: unknown): ParseResult<NativeToJsMessage> {
  return parseEnvelope<NativeToJsMessage>(raw, isNativeToJsType);
}
