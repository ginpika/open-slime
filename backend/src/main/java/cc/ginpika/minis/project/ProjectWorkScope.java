package cc.ginpika.minis.project;

/** The runtime, never model arguments, binds the TODO of a concurrent execution. */
public final class ProjectWorkScope implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;
    private ProjectWorkScope(String todo) { previous = CURRENT.get(); CURRENT.set(todo); }
    public static ProjectWorkScope bind(String todo) { return new ProjectWorkScope(todo); }
    public static String get() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
