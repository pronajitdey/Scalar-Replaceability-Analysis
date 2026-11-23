class A { int x; }

public class Test {
    static void use(A a) {
        System.out.println(a.x); // only reads
    }

    static void create() {
        A a = new A(); // O1 — allocated in create()
        use(a);        // passed to use() but only read
    }

    public static void main(String[] args) {
        create();
    }
}