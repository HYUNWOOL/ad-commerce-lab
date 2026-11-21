# 3. 이벤트 명세

## 3.1 봉투(envelope)

모든 이벤트는 같은 JSON 봉투를 쓴다. 값은 문자열로 직렬화해 Kafka에 넣는다.

```json
{
  "eventId": "uuid",
  "type": "OrderPlaced",
  "version": 1,
  "occurredAt": "2026-11-21T10:00:00Z",
  "aggregateId": "주문·캠페인·결제 ID",
  "payload": { }
}
```

- 메시지 키 = `aggregateId`. 같은 대상의 이벤트는 같은 파티션으로 가서 순서가 지켜진다.
- 필드를 추가하는 변경만 같은 version에서 허용한다. 의미가 바뀌면 version을 올린다.
- 소비자는 처리 전에 Inbox(`processed_events`)에 eventId를 넣어 본다. 이미 있으면 건너뛴다(같은 트랜잭션).

## 3.2 토픽

| 토픽 | 이벤트 | 발행 | 소비 | 키 |
| --- | --- | --- | --- | --- |
| campaign.order | OrderPlaced | campaign | payment | orderId |
| payment.result | PaymentApproved, PaymentFailed | payment | campaign, settlement | orderId |
| payment.refund | RefundCompleted | payment | campaign, settlement | orderId |
| campaign.lifecycle | CampaignActivated, CampaignPaused, CampaignResumed, RefundRequested | campaign | delivery, payment | campaignId |
| delivery.impression | ImpressionReceived(원시) | delivery 수집 API | delivery 집계 | campaignId |
| delivery.charge | CampaignCharged, BudgetExhausted | delivery | campaign, settlement | campaignId |

## 3.3 페이로드

| 이벤트 | 페이로드 |
| --- | --- |
| OrderPlaced | orderId, partnerId, productId, amount, cpvPrice, startDate, endDate |
| PaymentApproved | orderId, paymentId, amount, pgTransactionId |
| PaymentFailed | orderId, paymentId, reason(`DECLINED`·`PG_UNAVAILABLE`·`UNKNOWN_EXPIRED`) |
| CampaignActivated | campaignId, orderId, partnerId, budget, cpvPrice, startDate, endDate |
| RefundRequested | campaignId, orderId, paymentId, refundAmount(서버 계산) |
| RefundCompleted | orderId, paymentId, refundAmount |
| CampaignCharged | campaignId, partnerId, windowStart, impressions, amount, spentTotal |
| BudgetExhausted | campaignId, spentTotal |
