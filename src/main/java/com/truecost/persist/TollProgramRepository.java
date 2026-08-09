package com.truecost.persist;

import com.truecost.toll.strategy.ProgramType;
import com.truecost.toll.strategy.TollProgram;
import com.truecost.toll.strategy.TollRateBasis;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TollProgramRepository {

    private final JdbcClient jdbcClient;

    public TollProgramRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from toll_program").query(Integer.class).single();
    }

    /**
     * Every toll program a company offers, in the shape TollStrategyEngine expects.
     * fee_requires_usage is deliberately not read here, TollStrategyEngine already derives fee
     * gating from the usage day count being greater than zero, so carrying a second, possibly
     * inconsistent signal for the same fact would only invite the two to disagree.
     */
    public List<TollProgram> findByCompanyCode(String companyCode) {
        List<ProgramRow> rows = jdbcClient.sql("""
                        select rc.name as companyName, tp.program_name as programName,
                               tp.program_type::text as programType, tp.daily_fee_cents as dailyFeeCents,
                               tp.cap_cents as capCents, tp.toll_rate_basis::text as tollRateBasis,
                               tp.covers_congestion as coversCongestion, tp.effective_date as effectiveDate
                        from toll_program tp
                        join rental_company rc on rc.id = tp.company_id
                        where rc.code = :companyCode
                        order by tp.program_name
                        """)
                .param("companyCode", companyCode)
                .query(ProgramRow.class)
                .list();

        return rows.stream().map(TollProgramRepository::toTollProgram).toList();
    }

    private static TollProgram toTollProgram(ProgramRow row) {
        ProgramType type = switch (row.programType()) {
            case "USAGE_DAY" -> ProgramType.PER_CROSSING_USAGE_DAY;
            case "ALL_RENTAL_DAYS" -> ProgramType.PER_CROSSING_ALL_DAYS;
            case "UNLIMITED_DAILY" -> ProgramType.UNLIMITED_DAILY;
            default -> throw new IllegalStateException("unknown toll_program.program_type " + row.programType());
        };
        boolean feeCapped = row.capCents() != null;
        long feeCapCents = feeCapped ? row.capCents() : 0L;
        TollRateBasis tollRateBasis = "MAX_CASH_RATE".equals(row.tollRateBasis())
                ? TollRateBasis.MAX_CASH
                : TollRateBasis.EZPASS;

        return new TollProgram(row.companyName(), row.programName(), type, row.dailyFeeCents(),
                feeCapped, feeCapCents, tollRateBasis, row.coversCongestion(), row.effectiveDate());
    }

    private record ProgramRow(
            String companyName,
            String programName,
            String programType,
            long dailyFeeCents,
            Long capCents,
            String tollRateBasis,
            boolean coversCongestion,
            LocalDate effectiveDate) {
    }

    public int countDistinctCompanies() {
        return jdbcClient.sql("select count(distinct company_id) from toll_program").query(Integer.class).single();
    }

    public int insert(long companyId, String programName, String programType, long dailyFeeCents,
            String feeDayBasis, boolean feeRequiresUsage, Long capCents, boolean tollsIncluded,
            String tollRateBasis, boolean coversCongestion, LocalDate effectiveDate) {
        return jdbcClient.sql("""
                        insert into toll_program
                            (company_id, program_name, program_type, daily_fee_cents, fee_day_basis,
                             fee_requires_usage, cap_cents, tolls_included, toll_rate_basis, covers_congestion, effective_date)
                        values
                            (:companyId, :programName, :programType::program_type, :dailyFeeCents, :feeDayBasis::fee_day_basis,
                             :feeRequiresUsage, :capCents, :tollsIncluded, :tollRateBasis::toll_rate_basis, :coversCongestion, :effectiveDate)
                        """)
                .param("companyId", companyId)
                .param("programName", programName)
                .param("programType", programType)
                .param("dailyFeeCents", dailyFeeCents)
                .param("feeDayBasis", feeDayBasis)
                .param("feeRequiresUsage", feeRequiresUsage)
                .param("capCents", capCents)
                .param("tollsIncluded", tollsIncluded)
                .param("tollRateBasis", tollRateBasis)
                .param("coversCongestion", coversCongestion)
                .param("effectiveDate", effectiveDate)
                .update();
    }
}
