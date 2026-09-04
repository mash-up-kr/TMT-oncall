package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import com.tmt.oncall.trigger.IncidentDetected;
import com.tmt.oncall.trigger.ServiceDownDetected;
import com.tmt.oncall.trigger.ServiceRecovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 리포트를 채널에 올린다. 같은 건의 후속 보고는 첫 리포트에 열린 스레드로 들어간다 —
 * 채널에는 건마다 한 줄만 남고, 진행 상황은 스레드를 따라가면 된다.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    /** Discord 스레드 이름 상한. */
    private static final int THREAD_NAME_LIMIT = 100;

    private final DiscordGateway gateway;
    private final OncallStore store;

    public DiscordNotifier(DiscordGateway gateway, OncallStore store) {
        this.gateway = gateway;
        this.store = store;
    }

    public void reportDown(ServiceDownDetected event) {
        IncidentRef ref = IncidentRef.health(event.target().key());
        report(event.target().discordChannelId(), ref,
                "다운 — " + event.target().key(),
                IncidentReports.down(event), IncidentReports.buttonsFor(event));
    }

    public void reportRecovered(ServiceRecovered event) {
        IncidentRef ref = IncidentRef.health(event.target().key());
        report(event.target().discordChannelId(), ref,
                "다운 — " + event.target().key(),
                IncidentReports.recovered(event), List.of());
    }

    public void reportIncident(IncidentDetected event, Analysis analysis) {
        IncidentRef ref = IncidentRef.sentry(event.issue().id());
        report(event.target().discordChannelId(), ref,
                event.issue().shortId() + " " + event.issue().title(),
                IncidentReports.incident(event, analysis), IncidentReports.buttonsFor(analysis));
    }

    /** 기동·종료·예산 경고처럼 특정 건에 묶이지 않는 한 줄 알림. 스레드를 열지 않는다. */
    public void notice(String channelId, String line) {
        gateway.send(channelId, MessageSplitter.split(line), List.of(), null);
    }

    /** 이미 스레드가 있으면 그 안에, 없으면 채널에 올리고 스레드를 연다. */
    private void report(String channelId, IncidentRef ref, String threadName,
                        String body, List<ReportButton> buttons) {
        List<String> chunks = MessageSplitter.split(body);
        Optional<String> threadId = store.threadIdOf(ref.sourceKey(), ref.externalId())
                .filter(id -> !id.isBlank());
        if (threadId.isPresent()) {
            gateway.sendInThread(threadId.get(), chunks, buttons, ref);
            return;
        }

        String messageId = gateway.send(channelId, chunks, buttons, ref);
        String openedThreadId = gateway.openThread(channelId, messageId, threadName(threadName));
        store.markProcessed(ref.sourceKey(), ref.externalId(), openedThreadId);
        log.info("리포트 전송 — {}/{} 스레드 {}", ref.sourceKey(), ref.externalId(), openedThreadId);
    }

    private static String threadName(String name) {
        return name.length() <= THREAD_NAME_LIMIT ? name : name.substring(0, THREAD_NAME_LIMIT);
    }
}
