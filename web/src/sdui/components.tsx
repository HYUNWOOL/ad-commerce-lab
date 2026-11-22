import { useRef, useState, type ReactNode } from 'react';
import type { Ctx } from './types';

type Props = Record<string, any>;

/** 이 앱이 아는 컴포넌트 하나. version은 이 앱이 그릴 수 있는 최고 버전이다. */
export interface UiComponent {
  version: number;
  render: (props: Props, ctx: Ctx, version: number) => ReactNode;
}

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`;
const percent = (part: number, whole: number) => (whole > 0 ? Math.floor((part * 1000) / whole) / 10 : 0);

function Banner({ text, tone = 'info' }: Props) {
  return (
    <div className={`banner ${tone}`} role="note">
      {text}
    </div>
  );
}

function SettlementSummary({ title, month, paid, used, balance }: Props) {
  return (
    <div className="card">
      <h2>
        {title} <small>{month}</small>
      </h2>
      <dl className="figures">
        <div><dt>결제액</dt><dd>{won(paid)}</dd></div>
        <div><dt>사용액</dt><dd>{won(used)}</dd></div>
        <div><dt>남은 금액</dt><dd className="strong">{won(balance)}</dd></div>
      </dl>
    </div>
  );
}

function BudgetGauge({ title, budget, spent, campaigns }: Props) {
  const p = percent(spent, budget);
  const r = 42;
  const c = 2 * Math.PI * r;
  return (
    <div className="card gauge">
      <h2>{title}</h2>
      <svg viewBox="0 0 100 100" role="img" aria-label={`예산 사용률 ${p}%`}>
        <circle cx="50" cy="50" r={r} className="track" />
        <circle cx="50" cy="50" r={r} className="value" strokeDasharray={`${(c * p) / 100} ${c}`} transform="rotate(-90 50 50)" />
        <text x="50" y="55" textAnchor="middle">{p}%</text>
      </svg>
      <p className="muted">
        캠페인 {campaigns}개 · {won(spent)} / {won(budget)}
      </p>
    </div>
  );
}

const STATUS: Record<string, string> = { ACTIVE: '집행 중', EXHAUSTED: '예산 소진', PAUSED: '일시 정지', ENDED: '종료' };

function CampaignPerformanceList({ title, items }: Props, version: number) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {items.length === 0 && <p className="muted">아직 캠페인이 없어요</p>}
      <ul className="campaigns">
        {items.slice(0, 5).map((c: Props) => (
          <li key={c.campaignId}>
            <div className="row">
              <span className="id">#{c.campaignId.slice(0, 8)}</span>
              <span className={`status ${c.status}`}>{STATUS[c.status] ?? c.status}</span>
            </div>
            <div className="row muted">
              <span>사용 {won(c.spent)} / {won(c.budget)}</span>
              {version >= 2 && <span>노출 {c.chargedImpressions.toLocaleString('ko-KR')}회</span>}
            </div>
            {version >= 2 && (
              <div className="bar" aria-label={`사용률 ${c.progressBp / 100}%`}>
                <div style={{ width: `${c.progressBp / 100}%` }} />
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function ProductList({ title, items }: Props, ctx: Ctx) {
  return (
    <div className="card">
      <h2>{title}</h2>
      <ul className="products">
        {items.map((p: Props) => (
          <li key={p.id}>
            <label className={ctx.selectedProductId === p.id ? 'selected' : ''}>
              <input type="radio" name="product" checked={ctx.selectedProductId === p.id} onChange={() => ctx.selectProduct(p.id)} />
              <span className="name">{p.name}</span>
              <span className="muted">
                노출당 {won(p.cpvPrice)} · 최소 {won(p.minBudget)} · 최대 {p.maxDays}일
              </span>
            </label>
          </li>
        ))}
      </ul>
    </div>
  );
}

const isoDate = (d: Date) => d.toISOString().slice(0, 10);

/** 구매 폼. 같은 입력으로 다시 누르면 같은 멱등키를 써서 주문이 두 번 생기지 않는다(M1 주문 API). */
function PurchaseForm({ props, ctx }: { props: Props; ctx: Ctx }) {
  const [budget, setBudget] = useState<number>(props.defaultBudget ?? 300000);
  const [days, setDays] = useState<number>(props.defaultDays ?? 7);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const key = useRef<{ input: string; value: string } | null>(null);

  async function submit() {
    if (ctx.selectedProductId == null) return;
    const start = new Date(Date.now() + 86_400_000);
    const order = { productId: ctx.selectedProductId, budget, startDate: isoDate(start), endDate: isoDate(new Date(start.getTime() + (days - 1) * 86_400_000)) };
    const input = JSON.stringify(order);
    if (key.current?.input !== input) key.current = { input, value: crypto.randomUUID() };
    setBusy(true);
    const result = await ctx.placeOrder(order, key.current.value);
    setBusy(false);
    setMessage(result.ok ? `주문을 받았어요(${result.id.slice(0, 8)}, ${result.status === 'PAYMENT_PENDING' ? '결제 확인 중' : result.status})` : result.message);
    if (result.ok) key.current = null;
  }

  return (
    <div className="card">
      <h2>{props.title}</h2>
      <label className="field">
        예산(원)
        <input type="number" min={0} step={10000} value={budget} onChange={(e) => setBudget(Number(e.target.value))} />
      </label>
      <label className="field">
        기간(일)
        <input type="number" min={1} max={30} value={days} onChange={(e) => setDays(Number(e.target.value))} />
      </label>
      <button className="primary" disabled={busy || ctx.selectedProductId == null} onClick={submit}>
        {ctx.selectedProductId == null ? '상품을 먼저 고르세요' : props.submitLabel}
      </button>
      {message && <p className="result">{message}</p>}
    </div>
  );
}

function ActionButton({ label, screen }: Props, ctx: Ctx) {
  return (
    <button className="secondary" onClick={() => ctx.navigate(screen)}>
      {label}
    </button>
  );
}

/** 서버가 보낸 대체 표시. 출처 장애(SOURCE_UNAVAILABLE)나 앱이 모르는 컴포넌트(UNSUPPORTED_COMPONENT). */
export function Fallback({ title, message, reason }: Props) {
  return (
    <div className="card fallback" data-reason={reason}>
      {title && <h2>{title}</h2>}
      <p>{message}</p>
    </div>
  );
}

const ALL: Record<string, UiComponent> = {
  Banner: { version: 1, render: (p) => <Banner {...p} /> },
  SettlementSummary: { version: 1, render: (p) => <SettlementSummary {...p} /> },
  BudgetGauge: { version: 1, render: (p) => <BudgetGauge {...p} /> },
  CampaignPerformanceList: { version: 2, render: (p, _ctx, v) => CampaignPerformanceList(p, v) },
  ActionButton: { version: 1, render: (p, ctx) => ActionButton(p, ctx) },
  ProductList: { version: 1, render: (p, ctx) => ProductList(p, ctx) },
  PurchaseForm: { version: 1, render: (p, ctx) => <PurchaseForm props={p} ctx={ctx} /> },
  Fallback: { version: 1, render: (p) => <Fallback {...p} /> },
};

export type ClientMode = 'new' | 'old';

/** old는 BudgetGauge가 생기기 전, 성과 목록 v1까지만 아는 앱이다(S11 흉내). */
export function registryFor(mode: ClientMode): Record<string, UiComponent> {
  if (mode === 'new') return ALL;
  const { BudgetGauge: _removed, ...rest } = ALL;
  return { ...rest, CampaignPerformanceList: { ...ALL.CampaignPerformanceList, version: 1 } };
}

/** 서버에 알리는 헤더 값. 예: Banner@1,CampaignPerformanceList@2 (ADR-0009 4번). */
export function clientComponents(registry: Record<string, UiComponent>): string {
  return Object.entries(registry)
    .map(([name, c]) => `${name}@${c.version}`)
    .join(',');
}
