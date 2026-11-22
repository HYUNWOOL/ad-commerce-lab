import { cleanup, render, screen as view } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clientComponents, registryFor, type UiComponent } from './components';
import { ScreenView } from './Renderer';
import type { Ctx, Screen } from './types';

const ctx: Ctx = { navigate: vi.fn(), selectedProductId: null, selectProduct: vi.fn(), placeOrder: vi.fn() };

function screenOf(...sections: Screen['sections']): Screen {
  return { screenId: 'home', revision: 1, title: '내 광고', generatedAt: '', sections };
}

function sectionTypes(container: HTMLElement) {
  return [...container.querySelectorAll('section')].map((s) => [s.dataset.sectionId, s.querySelector('[data-reason]')?.getAttribute('data-reason') ?? 'ok']);
}

afterEach(cleanup);

describe('ScreenView', () => {
  it('서버가 보낸 순서대로 섹션을 그린다', () => {
    const { container } = render(
      <ScreenView
        ctx={ctx}
        components={registryFor('new')}
        screen={screenOf(
          { id: 'notice', type: 'Banner', version: 1, props: { text: '공지' } },
          { id: 'settlement', type: 'SettlementSummary', version: 1, props: { title: '이번 달 정산', month: '2026-11', paid: 300000, used: 5000, balance: 295000 } },
        )}
      />,
    );
    expect(sectionTypes(container)).toEqual([['notice', 'ok'], ['settlement', 'ok']]);
    expect(view.getByText('295,000원')).toBeTruthy();
  });

  it('S11 앱이 모르는 종류를 받으면 그 섹션만 대체 표시하고 나머지는 그린다', () => {
    const { container } = render(
      <ScreenView
        ctx={ctx}
        components={registryFor('old')}
        screen={screenOf(
          { id: 'notice', type: 'Banner', version: 1, props: { text: '공지' } },
          { id: 'gauge', type: 'BudgetGauge', version: 1, props: { title: '예산 사용률', budget: 100, spent: 50, campaigns: 1 } },
          { id: 'cta', type: 'ActionButton', version: 1, props: { label: '상품 보기', screen: 'products' } },
        )}
      />,
    );
    expect(sectionTypes(container)).toEqual([['notice', 'ok'], ['gauge', 'UNKNOWN_ON_CLIENT'], ['cta', 'ok']]);
  });

  it('섹션 하나가 그리다 터져도 그 섹션만 대체 표시한다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const broken: Record<string, UiComponent> = {
      ...registryFor('new'),
      Broken: { version: 1, render: () => { throw new Error('boom'); } },
    };
    const { container } = render(
      <ScreenView
        ctx={ctx}
        components={broken}
        screen={screenOf(
          { id: 'broken', type: 'Broken', version: 1, props: {} },
          { id: 'notice', type: 'Banner', version: 1, props: { text: '공지는 보인다' } },
        )}
      />,
    );
    expect(sectionTypes(container)).toEqual([['broken', 'RENDER_ERROR'], ['notice', 'ok']]);
    expect(view.getByText('공지는 보인다')).toBeTruthy();
  });

  it('서버가 높은 버전을 보내도 앱이 아는 버전까지만 그린다', () => {
    render(
      <ScreenView
        ctx={ctx}
        components={registryFor('old')}
        screen={screenOf({
          id: 'performance', type: 'CampaignPerformanceList', version: 2,
          props: { title: '캠페인 성과', items: [{ campaignId: 'c1234567-x', status: 'ACTIVE', budget: 1000, spent: 500, chargedImpressions: 10, progressBp: 5000 }] },
        })}
      />,
    );
    expect(view.queryByText(/노출 10회/)).toBeNull(); // v2에만 있는 노출 수
    expect(view.getByText(/사용 500원/)).toBeTruthy();
  });
});

describe('clientComponents', () => {
  it('구버전 앱은 BudgetGauge를 모르고 성과 목록은 v1까지만 안다고 알린다', () => {
    expect(clientComponents(registryFor('old'))).not.toContain('BudgetGauge');
    expect(clientComponents(registryFor('old'))).toContain('CampaignPerformanceList@1');
    expect(clientComponents(registryFor('new'))).toContain('BudgetGauge@1');
    expect(clientComponents(registryFor('new'))).toContain('CampaignPerformanceList@2');
  });
});
