class A {
    int f;
}

public class Test {
    public static void foo(A x) {
        System.out.println(x.f);
    }

    public static void bar(A x) {
        x.f = 100;
    }

    public static void main(String[] args) {
        A a = new A(); // O1
        A b = new A(); // O2

        foo(a); // Line L1
        foo(b); // Line L2
        bar(b); // Line L3
    }
}