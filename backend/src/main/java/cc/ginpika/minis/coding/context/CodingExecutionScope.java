package cc.ginpika.minis.coding.context;

/** Only server-created execution metadata enters this scope; never model-supplied identity. */
public final class CodingExecutionScope implements AutoCloseable {
    public record Identity(String id,String role,int rootAttempt,int attempt) {}
    private static final ThreadLocal<Identity> CURRENT=new ThreadLocal<>();
    private final Identity previous;
    private CodingExecutionScope(Identity value){previous=CURRENT.get();CURRENT.set(value);}
    public static CodingExecutionScope bind(Identity value){return new CodingExecutionScope(value);}
    public static Identity get(){return CURRENT.get();}
    @Override public void close(){if(previous==null){CURRENT.remove();}else{CURRENT.set(previous);}}
}
