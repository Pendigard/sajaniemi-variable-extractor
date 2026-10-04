function collectionCases(value) {
  const organizerSort = [3, 1, 2];
  organizerSort.sort();

  const organizerReverse = [1, 2, 3];
  organizerReverse.reverse();

  const fixedCollection = [1, 2, 3];
  print(fixedCollection[0]);

  const containerPush = [];
  containerPush.push(value);

  const containerBoth = [];
  containerBoth.push(value);
  containerBoth.pop();
  containerBoth.sort();

  const transformedOnly = [value];
  transformedOnly[0] = normalize(transformedOnly[0]);

  const replacedOnly = [value];
  replacedOnly[0] = value;

  let pathText = "root";
  pathText += "/child";

  let collectionByNameOnly = value;
  remove(collectionByNameOnly);
  return [organizerSort, organizerReverse, containerPush, containerBoth];
}
