import { useEffect, useMemo, useState } from 'react';
import { decideApproval, fetchScreen, placeOrder } from './api';
import { clientComponents, registryFor, type ClientMode } from './sdui/components';
import { ScreenView } from './sdui/Renderer';
import type { Ctx, Screen } from './sdui/types';

// ?screen=products&client=old 로 바로 열 수 있다(실험 화면 캡처용).
const params = new URLSearchParams(window.location.search);

export function App() {
  const [screenId, setScreenId] = useState(params.get('screen') ?? 'home');
  const [mode, setMode] = useState<ClientMode>(params.get('client') === 'old' ? 'old' : 'new');
  const [screen, setScreen] = useState<Screen | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedProductId, selectProduct] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const components = useMemo(() => registryFor(mode), [mode]);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    fetchScreen(screenId, clientComponents(components))
      .then((s) => !cancelled && setScreen(s))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [screenId, components, reload]);

  const ctx: Ctx = {
    navigate: setScreenId, selectedProductId, selectProduct, placeOrder, decideApproval,
    refresh: () => setReload((n) => n + 1),
  };

  return (
    <div className="app">
      <header>
        <strong>Nearby 파트너</strong>
        <nav>
          <button className={screenId === 'home' ? 'on' : ''} onClick={() => setScreenId('home')}>홈</button>
          <button className={screenId === 'products' ? 'on' : ''} onClick={() => setScreenId('products')}>광고 상품</button>
          <button className={screenId === 'approvals' ? 'on' : ''} onClick={() => setScreenId('approvals')}>승인 요청</button>
        </nav>
      </header>
      <div className="toolbar">
        <label>
          <input type="checkbox" checked={mode === 'old'} onChange={(e) => setMode(e.target.checked ? 'old' : 'new')} /> 구버전 앱으로 보기
        </label>
        <button onClick={() => setReload((n) => n + 1)}>새로고침</button>
      </div>
      {error && <p className="error">{error}</p>}
      {screen && (
        <>
          <h1>{screen.title}</h1>
          <ScreenView screen={screen} ctx={ctx} components={components} />
          <footer className="muted">
            화면 리비전 {screen.revision} · {mode === 'old' ? '구버전 앱' : '최신 앱'}
          </footer>
        </>
      )}
    </div>
  );
}
