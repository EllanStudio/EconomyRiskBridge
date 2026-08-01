package cn.superiormc.economyriskbridge.model;

public record CounterSnapshot(
    long senderDaily,
    long receiverDaily,
    long pairDaily,
    long clusterDaily,
    int velocityCount,
    int fanInDistinct,
    int fanOutDistinct,
    boolean reverseFlow,
    boolean circularFlow,
    long senderRecentIncoming,
    long receiverRecentOutgoing,
    int repeatedAmountCount
) {
}
