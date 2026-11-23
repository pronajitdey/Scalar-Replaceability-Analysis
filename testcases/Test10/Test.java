class A {
    A x;
    void foo(A o) {
        System.out.println(o.x.x);
    }
    void bar(A o) {
        o.x.x = new A();
    }
}

public class Test {
    public static void main(String[] args) {
        A o1 = new A();
        A o2 = new A();
        A o3 = new A();
        o1.x = o3;
        o2.x = o1;
        o2.foo(o2);
        o1.x = new A();
        o2.bar(o2);
    }
}