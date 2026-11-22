/** experience가 내려주는 화면(ADR-0009). 클라이언트는 섹션을 순서대로 그리기만 한다. */
export interface Section {
  id: string;
  type: string;
  version: number;
  props: Record<string, unknown>;
}

export interface Screen {
  screenId: string;
  revision: number;
  title: string;
  generatedAt: string;
  sections: Section[];
}

export interface OrderRequest {
  productId: number;
  budget: number;
  startDate: string;
  endDate: string;
}

export type OrderResult = { ok: true; id: string; status: string } | { ok: false; message: string };

export type DecisionResult = { ok: true; status: string } | { ok: false; message: string };

/** 섹션끼리 나누는 상태와 행동. 상품 목록에서 고른 상품을 구매 폼이 쓴다. */
export interface Ctx {
  navigate: (screenId: string) => void;
  selectedProductId: number | null;
  selectProduct: (id: number) => void;
  placeOrder: (order: OrderRequest, idempotencyKey: string) => Promise<OrderResult>;
  /** 에이전트가 만든 승인 요청을 사람이 승인·거절한다(ADR-0010 4번). */
  decideApproval: (id: string, action: 'approve' | 'reject') => Promise<DecisionResult>;
  /** 화면을 서버에서 다시 받는다. */
  refresh: () => void;
}
