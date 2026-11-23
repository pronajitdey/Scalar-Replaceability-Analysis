class A { int x; }

public class Test {
    static A make() {
        A a = new A(); // O1 — returned
        A b = new A(); // O2 — stays local in make()
        b.x = 99;      // field written on b
        return a;      // only a is returned
    }

    public static void main(String[] args) {
        A r = make();
    }
}