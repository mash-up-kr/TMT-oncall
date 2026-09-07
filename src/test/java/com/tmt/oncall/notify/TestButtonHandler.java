package com.tmt.oncall.notify;

import com.tmt.oncall.store.OncallStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

/**
 * 다른 패키지의 테스트가 진짜 {@link ButtonHandler}를 세울 수 있게 열어주는 통로.
 * 실행 경로는 나중에 끼운다 — 운영과 마찬가지로 둘이 서로를 참조하기 때문이다.
 */
public final class TestButtonHandler {

    private TestButtonHandler() {
    }

    public static ButtonHandler create(OncallStore store, Supplier<IncidentActions> actions) {
        return new ButtonHandler(store, new LazyProvider(actions));
    }

    private record LazyProvider(Supplier<IncidentActions> actions) implements ObjectProvider<IncidentActions> {

        @Override
        public IncidentActions getObject(Object... args) {
            return getObject();
        }

        @Override
        public IncidentActions getObject() {
            return actions.get();
        }

        @Override
        public IncidentActions getIfAvailable() {
            return actions.get();
        }

        @Override
        public IncidentActions getIfUnique() {
            return actions.get();
        }
    }
}
