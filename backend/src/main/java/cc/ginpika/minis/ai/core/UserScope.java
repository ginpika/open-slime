package cc.ginpika.minis.ai.core;

/** Authenticated actor captured by transport and carried across its execution thread. */
public final class UserScope implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
    private final String previous;
    private UserScope(String user){previous=CURRENT.get();if(user==null){CURRENT.remove();}else{CURRENT.set(user);}}
    public static UserScope bind(String user){return new UserScope(user);}
    public static String get(){return CURRENT.get();}
    @Override public void close(){if(previous==null){CURRENT.remove();}else{CURRENT.set(previous);}}
}
