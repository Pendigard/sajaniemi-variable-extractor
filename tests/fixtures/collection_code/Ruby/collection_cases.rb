def collection_cases(value)
  organizer_sort = [3, 1, 2]
  organizer_sort.sort!

  organizer_reverse = [1, 2, 3]
  organizer_reverse.reverse!

  fixed_collection = [1, 2, 3]
  puts fixed_collection[0]

  container_values = []
  container_values << value
  container_values.pop
  container_values.sort!

  transformed_only = [value]
  transformed_only[0] = normalize(transformed_only[0])

  replaced_only = [value]
  replaced_only[0] = value

  path_text = "root"
  path_text << "/child"

  collection_by_name_only = value
  remove(collection_by_name_only)
end
