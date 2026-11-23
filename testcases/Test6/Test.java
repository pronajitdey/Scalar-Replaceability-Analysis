class A { int x; }

public class Test {
    static A make() {
        A a = new A(); // O1 — allocated in make()
        return a;      // returned but field never written
    }

    public static void main(String[] args) {
        A r = make();
        System.out.println(r.x);
    }
}