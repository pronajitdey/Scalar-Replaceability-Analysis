class A { int x; }
class B {
    A a; 
    void foo(B b) {
        System.out.println(b.a.x); // or some read without lib methods
        // b.a.x = 10;
        return;
    }
}
public class Test {
    public static void main(String[] args) {
        A a = new A(); //O1
        B b = new B();  //O2
        b.a = a;
        b.foo(b);   // line 10
    }
}