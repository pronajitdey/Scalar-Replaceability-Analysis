class A { int x; }
public class Test {
    static void compute() {
        A a = new A(); // same line number both calls
        a.x = 10;
    }
    public static void main(String[] args) {
        compute();
        compute();
    }
}