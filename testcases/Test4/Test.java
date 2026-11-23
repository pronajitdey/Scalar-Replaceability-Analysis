class A { int x; }
class B {
    A a; 
    void foo(B b) {
        b.a.x = 10;
        return;
    }
}
class Test {
    public static void main(String[] args) {
        A a = new A(); //O1
        B b = new B();  //O2
        b.a = a;
        b.foo(b);   // line 10
    }
}