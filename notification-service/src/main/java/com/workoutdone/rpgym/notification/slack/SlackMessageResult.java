package com.workoutdone.rpgym.notification.slack;

/** Slack chat.postMessage 성공 응답에서 필요한 값만 뽑아둔 것. */
public record SlackMessageResult(
        String channel,
        String ts
) {
}
