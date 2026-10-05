function cumsum_positive(arr) {
    let total = 0;
    let has_negative = false;
    let cumsum = [];
    for (const elt of arr) {
        if (elt >= 0) {
            total += elt;
            cumsum.push(total);
        } else {
            has_negative = true;
        }
    }
    return [cumsum, has_negative];
}