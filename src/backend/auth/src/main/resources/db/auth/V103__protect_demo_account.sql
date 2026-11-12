ALTER TABLE public.app_accounts ADD COLUMN demo_locked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION public.protect_demo_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.demo_locked THEN
        RAISE EXCEPTION 'Shared demo account cannot be changed' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_demo_account BEFORE UPDATE OR DELETE ON public.app_accounts
    FOR EACH ROW EXECUTE FUNCTION public.protect_demo_account();
