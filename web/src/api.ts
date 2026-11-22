import type { OrderRequest, OrderResult, Screen } from './sdui/types';

/** 로컬 실험용 데모 키(gateway 설정의 값, 실제 비밀값 아님). 다른 파트너로 보려면 localStorage의 apiKey를 바꾼다. */
function apiKey(): string {
  try {
    return localStorage.getItem('apiKey') ?? 'demo-partner-key';
  } catch {
    return 'demo-partner-key';
  }
}

export async function fetchScreen(screenId: string, components: string): Promise<Screen> {
  const res = await fetch(`/api/screens/${encodeURIComponent(screenId)}`, {
    headers: { 'X-Api-Key': apiKey(), 'X-Client-Components': components },
  });
  if (!res.ok) throw new Error(`화면을 불러오지 못했어요(${res.status})`);
  return res.json();
}

export async function placeOrder(order: OrderRequest, idempotencyKey: string): Promise<OrderResult> {
  try {
    const res = await fetch('/api/orders', {
      method: 'POST',
      headers: { 'X-Api-Key': apiKey(), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(order),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok) return { ok: true, id: body.id, status: body.status };
    return { ok: false, message: body.detail ?? `주문하지 못했어요(${res.status})` };
  } catch {
    return { ok: false, message: '네트워크 오류예요. 같은 내용으로 다시 누르면 주문이 두 번 생기지 않아요' };
  }
}
