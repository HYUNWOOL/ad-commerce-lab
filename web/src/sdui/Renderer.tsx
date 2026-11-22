import { Component, type ReactNode } from 'react';
import { Fallback, type UiComponent } from './components';
import type { Ctx, Screen, Section } from './types';

/** 섹션 하나가 그리다 터져도 그 섹션만 대체 표시하고 나머지는 그린다(ADR-0009 5번). */
class SectionBoundary extends Component<{ children: ReactNode; sectionId: string }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown) {
    console.error(`섹션 ${this.props.sectionId}을(를) 그리지 못함`, error);
  }

  render() {
    return this.state.failed ? <Fallback message="이 영역을 표시하지 못했어요" reason="RENDER_ERROR" /> : this.props.children;
  }
}

function SectionView({ section, ctx, components }: { section: Section; ctx: Ctx; components: Record<string, UiComponent> }) {
  const component = components[section.type];
  // 서버가 맞춰 보내므로 보통은 생기지 않는다. 서버 설정이 틀려도 화면 전체가 깨지지 않게 한 번 더 막는다.
  if (!component) return <Fallback message="이 영역은 지금 볼 수 없어요" reason="UNKNOWN_ON_CLIENT" />;
  return <>{component.render(section.props, ctx, Math.min(section.version, component.version))}</>;
}

export function ScreenView({ screen, ctx, components }: { screen: Screen; ctx: Ctx; components: Record<string, UiComponent> }) {
  return (
    <main className="screen">
      {screen.sections.map((s) => (
        <section key={s.id} className="section" data-section-id={s.id} data-section-type={s.type}>
          <SectionBoundary sectionId={s.id}>
            <SectionView section={s} ctx={ctx} components={components} />
          </SectionBoundary>
        </section>
      ))}
    </main>
  );
}
