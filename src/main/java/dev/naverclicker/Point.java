package dev.naverclicker;

/** 클릭할 화면 좌표. 예약 후 값이 바뀌지 않도록 불변으로 둔다. */
public record Point(int x, int y) {
    @Override
    public String toString() {
        return "X = %d, Y = %d".formatted(x, y);
    }
}
