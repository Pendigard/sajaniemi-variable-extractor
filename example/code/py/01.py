def biggest_input_diff():
	prev = None 
	curr = None
	max_diff = 0
	step = 0
	while step < 100:
		step += 1
		prev = curr
		curr = int(input())

		if prev != None:
			max_diff = max(max_diff, curr - prev)
			

	return max_diff