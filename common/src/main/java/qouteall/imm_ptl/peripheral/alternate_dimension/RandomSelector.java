package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.mojang.datafixers.util.Pair;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class RandomSelector<T> {
    private Object[] entries;
    private int[] subWeightSum;
    private int weightSum;

    // 26.2: net.minecraft.util.Tuple is GONE; ported to the closest available pair type
    // com.mojang.datafixers.util.Pair (getA/getB -> getFirst/getSecond, new Tuple<>(a,b) ->
    // Pair.of(a,b)). Pair.of(element, weight) preserves IP's (first=element, second=weight) order.
    public RandomSelector(List<Pair<T, Integer>> data) {
        entries = data.stream().map(Pair::getFirst).toArray();

        subWeightSum = Helper.mapReduce(
            data.stream(),
            (preSum, curr) -> preSum + curr.getSecond(),
            new Helper.SimpleBox<>((Integer) 0)
        ).mapToInt(i -> i).toArray();
        
        weightSum = subWeightSum[subWeightSum.length - 1];
    }
    
    public T select(Random random) {
        int randomValue = random.nextInt(weightSum);
        
        return selectByRandomValue(randomValue);
    }
    
    public T selectByRandomValue(int randomValue) {
        int result = Arrays.binarySearch(
            subWeightSum,
            0, subWeightSum.length,
            randomValue
        );
        
        if (result >= 0) {
            return (T) entries[result + 1];
        }
        else {
            //result = -firstEleGreaterThanValue - 1
            int firstEleGreaterThanValue = -(result + 1);
            return (T) entries[firstEleGreaterThanValue];
        }
    }
    
    public static class Builder<A> {
        private ArrayList<Pair<A, Integer>> data = new ArrayList<>();

        public Builder() {
        }

        public Builder<A> add(int weight, A element) {
            data.add(Pair.of(element, weight));
            return this;
        }
        
        public RandomSelector<A> build() {
            return new RandomSelector<>(data);
        }
    }
    
}
