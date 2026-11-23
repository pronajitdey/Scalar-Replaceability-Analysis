class A { int x; }

public class Test {
    static A make() {
        A a = new A(); // O1 — allocated in make()
        a.x = 5;
        return a;
    }

    public static void main(String[] args) {
        A r = make();
        System.out.println(r.x);
    }
}