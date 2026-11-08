package app.senyap.rt.obs;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Konteks trace W3C ({@code traceparent}) lintas proses asinkron yang tidak diinstrumentasi otomatis:
 * sisi gateway: frame {@code msg.send} → REST api (header {@code traceparent}), dan Redis
 * ({@code RealtimeBus.Delivery}) → span {@code msg.new} ke socket penerima. Hanya ID trace/span yang dibawa,
 * tidak pernah isi pesan.
 */
/** Bean didaftarkan di {@code ObservabilityConfig}. */
public class Traces {

    public static final String HEADER = "traceparent";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final Tracer tracer;

    public Traces(ObjectProvider<OpenTelemetry> otel) {
        this.tracer = otel.getIfAvailable(OpenTelemetry::noop).getTracer("app.senyap");
    }

    /** {@code traceparent} span yang sedang aktif di thread ini, atau {@code null}. */
    public static String currentTraceparent() {
        return traceparent(Span.current());
    }

    static Context parent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), Map.of(HEADER, traceparent), GETTER);
    }

    /** Jalankan {@code body} di span baru yang anak dari {@code parentTraceparent} (atau root bila kosong). */
    public <T> T inSpan(String name, SpanKind kind, String parentTraceparent, Map<String, String> attributes,
                        Supplier<T> body) {
        Span span = tracer.spanBuilder(name).setSpanKind(kind).setParent(parent(parentTraceparent)).startSpan();
        attributes.forEach(span::setAttribute);
        try (var scope = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void inSpan(String name, SpanKind kind, String parentTraceparent, Runnable body) {
        inSpan(name, kind, parentTraceparent, Map.of(), () -> {
            body.run();
            return null;
        });
    }

    /** Untuk alur reaktif: span dimulai sekarang dan diakhiri pemanggil ({@link Span#end()}). */
    public Span start(String name, SpanKind kind, String parentTraceparent) {
        return tracer.spanBuilder(name).setSpanKind(kind).setParent(parent(parentTraceparent)).startSpan();
    }

    /** {@code traceparent} dari span tertentu, atau {@code null} bila span tidak valid (tracing mati). */
    public static String traceparent(Span span) {
        SpanContext sc = span.getSpanContext();
        return sc.isValid() ? "00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex() : null;
    }
}
