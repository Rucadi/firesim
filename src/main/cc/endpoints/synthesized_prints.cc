#ifdef PRINTWIDGET_struct_guard

#include "synthesized_prints.h"

synthesized_prints_t::synthesized_prints_t(
  simif_t* sim,
  std::vector<std::string> &args,
  PRINTWIDGET_struct * mmio_addrs,
  unsigned int print_count,
  const unsigned int* print_offsets,
  const char* const*  format_strings,
  const unsigned int* argument_counts,
  const unsigned int* argument_widths,
  unsigned int dma_address):
    endpoint_t(sim),
    mmio_addrs(mmio_addrs),
    print_count(print_count),
    print_offsets(print_offsets),
    format_strings(format_strings),
    argument_counts(argument_counts),
    argument_widths(argument_widths),
    dma_address(dma_address) {

  const char *printfilename = NULL;

  this->start_cycle = 0;
  this->cur_cycle = 0;
  this->end_cycle = ULONG_MAX;

  std::string printfile_arg  = std::string("+printfile=");
  std::string printstart_arg = std::string("+print-start=");
  std::string printend_arg   = std::string("+print-end=");

  for (auto &arg: args) {
      if (arg.find(printfile_arg) == 0) {
          printfilename = const_cast<char*>(arg.c_str()) + printfile_arg.length();
      }
      if (arg.find(printstart_arg) == 0) {
          char *str = const_cast<char*>(arg.c_str()) + printstart_arg.length();
          this->start_cycle = atol(str);
      }
      if (arg.find(printend_arg) == 0) {
          char *str = const_cast<char*>(arg.c_str()) + printend_arg.length();
          this->end_cycle = atol(str);
      }
  }

  if (printfilename) {
      this->printfile.open(printfilename);
      if (!this->printfile.is_open()) {
          fprintf(stderr, "Could not open print log file: %s\n", printfilename);
          abort();
      }
      this->printstream = &(this->printfile);
  } else {
      this->printstream = &std::cerr;
  }

  widths.resize(print_count);
  // Used to reconstruct the relative position of arguments in the flattened argument_widths array
  size_t arg_base_offset = 0;
  size_t print_bit_offset = 1; // The lsb of the current print in the packed token

  for (size_t p_idx = 0; p_idx < print_count; p_idx++ ) {

    auto print_args = new print_vars_t;
    size_t print_width = 1; // A running total of argument widths for this print, including an enable bit

    // Iterate through the arguments for this print
    for (size_t arg_idx = 0; arg_idx < argument_counts[p_idx]; arg_idx++) {
      size_t arg_width = argument_widths[arg_base_offset + arg_idx];
      widths[p_idx].push_back(arg_width);

      mpz_t* mask = (mpz_t*)malloc(sizeof(mpz_t));
      // Below is equivalent to  *mask = (1 << arg_width) - 1
      mpz_init(*mask);
      mpz_set_ui(*mask, 1);
      mpz_mul_2exp(*mask, *mask, arg_width);
      mpz_sub_ui(*mask, *mask, 1);

      print_args->data.push_back(mask);
      print_width += arg_width;
    }

    size_t aligned_offset = print_bit_offset / gmp_align_bits;
    size_t aligned_msw = (print_width + print_bit_offset) / gmp_align_bits;
    size_t rounded_size = aligned_msw - aligned_offset + 1;

    arg_base_offset += argument_counts[p_idx];
    masks.push_back(print_args);
    sizes.push_back(rounded_size);
    aligned_offsets.push_back(aligned_offset);
    bit_offset.push_back(print_bit_offset % gmp_align_bits);

    print_bit_offset += print_width;
  }
};

synthesized_prints_t::~synthesized_prints_t() {
  free(this->mmio_addrs);
  for (size_t i = 0 ; i < print_count ; i++) {
      delete masks[i];
  }
}

// Accepts the format string, and the masked arguments, and emits the formatted
// print to the desired stream
void synthesized_prints_t::print_format(const char* fmt, print_vars_t* vars) {
  size_t k = 0;
  while(*fmt) {
    if (*fmt == '%' && fmt[1] != '%') {
      mpz_t* value = vars->data[k];
      char* v = NULL;
      if (fmt[1] == 's') {
        size_t size;
        v = (char*)mpz_export(NULL, &size, 1, sizeof(char), 0, 0, *value);
        for (size_t j = 0 ; j < size ; j++) printstream->put(v[j]);
        fmt++;
      } else {
        switch(*(++fmt)) {
          // TODO: exhaustive?
          case 'h':
          case 'x': v = mpz_get_str(NULL, 16, *value); break;
          case 'd': v = mpz_get_str(NULL, 10, *value); break;
          case 'b': v = mpz_get_str(NULL, 2, *value); break;
          default: break;
        }
        if (v) (*printstream) << v;
      }
      free(v);
      fmt++;
      k++;
    } else if (*fmt == '%') {
      printstream->put(*(++fmt));
      fmt++;
    } else if (*fmt == '\\' && fmt[1] == 'n') {
      printstream->put('\n');
      fmt += 2;
    } else {
      printstream->put(*fmt);
      fmt++;
    }
  }
  assert(k == vars->data.size());
}

// Returns true if at least one print in the buffer is enabled in this cycle
bool has_enabled_print(char * buf) { return (buf[0] & 1); }

// Iterates through the DMA flits (each is one token); checking if their are enabled prints
void synthesized_prints_t::process_tokens() {
  size_t batch_bytes = batch_beats * beat_bytes;
  char buf[batch_bytes];
  pull(dma_address, (char*)buf, batch_bytes);
  for (size_t idx = 0; idx < batch_bytes; idx += token_bytes ) {
    if (has_enabled_print(&buf[idx]) && cur_cycle >= start_cycle && cur_cycle <= end_cycle) {
      show_prints(&buf[idx]);
    }
    cur_cycle++;
  }
}

// Returns true if the print at the current offset is enabled in this cycle
bool synthesized_prints_t::current_print_enabled(gmp_align_t * buf, size_t offset) {
  return (buf[0] & (1 << (offset)));
}

// Finds enabled prints in a token
void synthesized_prints_t::show_prints(char * buf) {
  for (size_t i = 0 ; i < print_count; i++) {
    gmp_align_t* data = ((gmp_align_t*)buf) + aligned_offsets[i];
    // First bit is enable
    if (current_print_enabled(data, bit_offset[i])) {
      mpz_t print;
      mpz_init(print);
      mpz_import(print, sizes[i], -1, sizeof(gmp_align_t), 0, 0, data);
      mpz_fdiv_q_2exp(print, print, bit_offset[i] + 1);

      print_vars_t vars;
      size_t num_args = argument_counts[i];
      for (size_t arg = 0 ; arg < num_args ; arg++) {
        mpz_t* var = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_t* mask = masks[i]->data[arg];
        mpz_init(*var);
        // *var = print & *mask
        mpz_and(*var, print, *mask);
        vars.data.push_back(var);
        // print = print >> width
        mpz_fdiv_q_2exp(print, print,widths[i][arg]);
      }
      print_format(format_strings[i], &vars);
      mpz_clear(print);
    }
  }
}

void synthesized_prints_t::tick() {
  // Pull batch_tokens from the FPGA if at least that many are avaiable
  // Assumes 1:1 token to dma-beat size
  if ((read(mmio_addrs->outgoing_count) > batch_beats)) {
    process_tokens();
  }
}

#endif // PRINTWIDGET_struct_guard
