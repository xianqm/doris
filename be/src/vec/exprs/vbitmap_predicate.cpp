// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "vec/exprs/vbitmap_predicate.h"

#include <cstddef>
#include <utility>

#include "exprs/bitmapfilter_predicate.h"
#include "vec/columns/column.h"
#include "vec/columns/column_nullable.h"
#include "vec/columns/column_vector.h"
#include "vec/common/string_ref.h"
#include "vec/core/block.h"
#include "vec/core/column_numbers.h"
#include "vec/core/column_with_type_and_name.h"
#include "vec/core/columns_with_type_and_name.h"
#include "vec/core/types.h"
#include "vec/data_types/data_type.h"

namespace doris {
class RowDescriptor;
class RuntimeState;
class TExprNode;

} // namespace doris

namespace doris::vectorized {
#include "common/compile_check_begin.h"

class VExprContext;

vectorized::VBitmapPredicate::VBitmapPredicate(const TExprNode& node)
        : VExpr(node), _filter(nullptr), _expr_name("bitmap_predicate") {}

doris::Status vectorized::VBitmapPredicate::prepare(doris::RuntimeState* state,
                                                    const RowDescriptor& desc,
                                                    vectorized::VExprContext* context) {
    RETURN_IF_ERROR_OR_PREPARED(VExpr::prepare(state, desc, context));

    if (_children.size() != 1) {
        return Status::InternalError("Invalid argument for VBitmapPredicate.");
    }

    ColumnsWithTypeAndName argument_template;
    argument_template.reserve(_children.size());
    for (auto child : _children) {
        auto column = child->data_type()->create_column();
        argument_template.emplace_back(std::move(column), child->data_type(), child->expr_name());
    }
    _prepare_finished = true;
    return Status::OK();
}

doris::Status vectorized::VBitmapPredicate::open(doris::RuntimeState* state,
                                                 vectorized::VExprContext* context,
                                                 FunctionContext::FunctionStateScope scope) {
    DCHECK(_prepare_finished);
    RETURN_IF_ERROR(VExpr::open(state, context, scope));
    _open_finished = true;
    return Status::OK();
}

doris::Status vectorized::VBitmapPredicate::execute(vectorized::VExprContext* context,
                                                    doris::vectorized::Block* block,
                                                    int* result_column_id) {
    DCHECK(_open_finished || _getting_const_col);
    doris::vectorized::ColumnNumbers arguments(_children.size());
    for (int i = 0; i < _children.size(); ++i) {
        int column_id = -1;
        RETURN_IF_ERROR(_children[i]->execute(context, block, &column_id));
        arguments[i] = column_id;
    }
    // call function
    uint32_t num_columns_without_result = block->columns();
    auto res_data_column = ColumnUInt8::create(block->rows());

    ColumnPtr argument_column =
            block->get_by_position(arguments[0]).column->convert_to_full_column_if_const();
    size_t sz = argument_column->size();
    res_data_column->resize(sz);
    auto* ptr = res_data_column->get_data().data();

    if (argument_column->is_nullable()) {
        auto column_nested =
                assert_cast<const ColumnNullable*>(argument_column.get())->get_nested_column_ptr();
        auto column_nullmap = assert_cast<const ColumnNullable*>(argument_column.get())
                                      ->get_null_map_column_ptr();
        _filter->find_batch(column_nested->get_raw_data().data,
                            (uint8_t*)column_nullmap->get_raw_data().data, sz, ptr);
    } else {
        _filter->find_batch(argument_column->get_raw_data().data, nullptr, sz, ptr);
    }

    if (_data_type->is_nullable()) {
        auto null_map = ColumnUInt8::create(block->rows(), 0);
        block->insert({ColumnNullable::create(std::move(res_data_column), std::move(null_map)),
                       _data_type, _expr_name});
    } else {
        block->insert({std::move(res_data_column), _data_type, _expr_name});
    }
    *result_column_id = num_columns_without_result;
    return Status::OK();
}

void vectorized::VBitmapPredicate::close(vectorized::VExprContext* context,
                                         FunctionContext::FunctionStateScope scope) {
    VExpr::close(context, scope);
}

const std::string& vectorized::VBitmapPredicate::expr_name() const {
    return _expr_name;
}

void vectorized::VBitmapPredicate::set_filter(std::shared_ptr<BitmapFilterFuncBase> filter) {
    _filter = filter;
}

#include "common/compile_check_end.h"
} // namespace doris::vectorized