function flowRoles(values, condition, count) {
  let rising = false;
  let falling = true;
  let inert = true;
  let toggled = false;
  let reverted = false;
  let total = 0;
  let product = 1;
  let phased = 0;
  let counter = 0;
  let replaced = 0;
  let transformed_score = 0;
  let finalized_center = 0;
  let stable_only = 1;
  for (const value of values) {
    rising = rising || condition;
    falling = falling && condition;
    inert = inert || condition;
    toggled = !toggled;
    reverted = true;
    reverted = false;
    total += value;
    product *= value;
    transformed_score *= 5;
    transformed_score += value;
    finalized_center += value;
    stable_only *= 5;
    phased += value;
    if (value < 0) phased = 0;
    counter += 1;
    replaced = value;
  }
  finalized_center /= count;
  return [rising, falling, inert, toggled, reverted, total, product, phased, counter,
          replaced, transformed_score, finalized_center, stable_only];
}

function parameterAccumulator(accumulator, values) {
  for (const value of values) accumulator += value;
  return accumulator;
}

function infinityMinimum(values) {
  let infinity_minimum = Infinity;
  for (const value of values) {
    if (value < infinity_minimum) infinity_minimum = value;
  }
  return infinity_minimum;
}

function projectedCandidates(items) {
  let indexed_maximum = 0;
  let indexed_minimum = 0;
  let mismatched_projection = 0;
  let mismatched_index = 0;
  let impure_projection = 0;
  for (let i = 0; i < items.length; i++) {
    if (indexed_maximum <= items[i].score) indexed_maximum = items[i].score;
    if (indexed_minimum >= items[i].score) indexed_minimum = items[i].score;
    if (mismatched_projection <= items[i].score) mismatched_projection = items[i].rank;
    if (mismatched_index <= items[i].score) mismatched_index = items[i + 1].score;
    if (impure_projection <= items[i].score) impure_projection = load(items[i]).score;
  }
  return [indexed_maximum, indexed_minimum];
}
