-- 승인 요청 화면(M5, ADR-0010 4번). 에이전트가 위임 한도를 넘는 구매를 하려 하면 여기서 파트너가 승인·거절한다.
-- 새 화면이라 기존 리비전과 겹치지 않는다. 앱 배포 없이 서버 설정만으로 생기는 화면이다(ADR-0009).
INSERT INTO screen_revisions (screen_id, revision, layout, created_by) VALUES
('approvals', 1, '{
  "title": "승인 요청",
  "sections": [
    {"id": "notice", "type": "Banner", "version": 1,
     "props": {"text": "에이전트가 위임 한도를 넘는 구매를 하려 하면 여기서 멈춥니다. 승인해야 주문됩니다.", "tone": "info"}},
    {"id": "approvals", "type": "ApprovalList", "version": 1, "props": {"title": "기다리는 요청"},
     "fallbackText": "앱을 업데이트하면 승인 요청을 볼 수 있어요"}
  ]
}', 'seed');
